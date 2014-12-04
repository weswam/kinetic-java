/**
 * 
 * Copyright (C) 2014 Seagate Technology.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */
package com.seagate.kinetic.simulator.io.provider.nio;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.seagate.kinetic.common.lib.KineticMessage;
import com.seagate.kinetic.proto.Kinetic.Command.MessageType;
import com.seagate.kinetic.simulator.internal.ConnectionInfo;
import com.seagate.kinetic.simulator.internal.FaultInjectedCloseConnectionException;
import com.seagate.kinetic.simulator.internal.InvalidBatchException;
import com.seagate.kinetic.simulator.internal.SimulatorEngine;
import com.seagate.kinetic.simulator.io.provider.spi.MessageService;

/**
 *
 * @author chiaming
 */
public class NioMessageServiceHandler extends
		SimpleChannelInboundHandler<KineticMessage> {

	private static final Logger logger = Logger
			.getLogger(NioMessageServiceHandler.class.getName());

    private static final String SEP = "-";

	private MessageService lcservice = null;

	private boolean enforceOrdering = false;

	private NioQueuedRequestProcessRunner queuedRequestProcessRunner = null;

    // key = connId + "-" + batchId
    private Map<String, BatchQueue> batchMap = new ConcurrentHashMap<String, BatchQueue>();

	private static boolean faultInjectCloseConnection = Boolean
			.getBoolean(FaultInjectedCloseConnectionException.FAULT_INJECT_CLOSE_CONNECTION);

    private boolean isSecureChannel = false;

    public NioMessageServiceHandler(MessageService lcservice2,
            boolean isSecureChannel) {
		this.lcservice = lcservice2;

        this.isSecureChannel = isSecureChannel;

		this.enforceOrdering = lcservice.getServiceConfiguration()
				.getMessageOrderingEnforced();

		if (this.enforceOrdering) {
			this.queuedRequestProcessRunner = new NioQueuedRequestProcessRunner(
					lcservice);
		}
	}
	
	@Override
	public void channelActive (ChannelHandlerContext ctx) throws Exception {
	    super.channelActive(ctx);
	    
	    // register connection info with the channel handler context
        @SuppressWarnings("unused")
        ConnectionInfo info = this.lcservice.registerNewConnection(ctx);
	    
	    //logger.info("***** connection registered., sent UNSOLICITEDSTATUS with cid = " + info.getConnectionId());
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx,
			KineticMessage request)
			throws Exception {

		if (faultInjectCloseConnection) {
			throw new FaultInjectedCloseConnectionException(
					"Fault injected for the simulator");
		}
		
		// set ssl channel flag to false
        request.setIsSecureChannel(isSecureChannel);
		
		// check if conn id is set
		NioConnectionStateManager.checkIfConnectionIdSet(ctx, request);

        // add to queue if batchQueue has started
        if (this.shouldAddToBatch(ctx, request)) {
            // the command was queued until END_BATCH is received
            return;
        }

        // check if this is a start batchQueue message
        if (this.isStartBatch(ctx, request)) {
            this.createBatchQueue(ctx, request);
        } else if (this.isEndBatch(ctx, request)) {
            this.processBatchQueue(ctx, request);
        } else if (this.isAbortBatch(ctx, request)) {
            this.processBatchAbort(ctx, request);
        }

        // process regular request
        processRequest(ctx, request);
    }

    private void processRequest(ChannelHandlerContext ctx,
            KineticMessage request) throws InterruptedException {

        if (enforceOrdering) {
            // process request sequentially
            queuedRequestProcessRunner.processRequest(ctx, request);
        } else {
            // each request is independently processed
            RequestProcessRunner rpr = null;
            rpr = new RequestProcessRunner(lcservice, ctx, request);
            this.lcservice.execute(rpr);
        }
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
		logger.log(Level.WARNING, "Unexpected exception from downstream.",
				cause);

		// close process runner
		if (this.queuedRequestProcessRunner != null) {
			this.queuedRequestProcessRunner.close();
		}

		// close context
		ctx.close();
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
	    
        // remove connection info of the channel handler context from conn info
        // map
        ConnectionInfo info = SimulatorEngine.removeConnectionInfo(ctx);

        this.batchMap = null;

        logger.info("connection info is removed, id=" + info.getConnectionId()
                + ", is secure channel=" + this.isSecureChannel);
	}

    private boolean isStartBatch(ChannelHandlerContext ctx,
            KineticMessage request) {

        if (request.getCommand().getHeader().getMessageType() == MessageType.START_BATCH) {
            request.setIsBatchMessage(true);
            return true;
        }

        return false;
    }

    private boolean isEndBatch(ChannelHandlerContext ctx, KineticMessage request) {

        if (request.getCommand().getHeader().getMessageType() == MessageType.END_BATCH) {

            request.setIsBatchMessage(true);

            return true;
        }

        return false;
    }

    private boolean isAbortBatch(ChannelHandlerContext ctx,
            KineticMessage request) {

        if (request.getCommand().getHeader().getMessageType() == MessageType.ABORT_BATCH) {

            request.setIsBatchMessage(true);

            return true;
        }

        return false;
    }

    private synchronized void createBatchQueue(ChannelHandlerContext ctx,
            KineticMessage request) {

        String key = request.getCommand().getHeader().getConnectionID() + SEP
                + request.getCommand().getHeader().getBatchID();

        BatchQueue batchQueue = this.batchMap.get(key);

        if (batchQueue == null) {
            batchQueue = new BatchQueue(request);
            this.batchMap.put(key, batchQueue);
        } else {
            // concurrent start batch is not allowed
            throw new RuntimeException("batch already started");
        }

        logger.info("batch queue created for key: " + key);
    }

    private boolean shouldAddToBatch(ChannelHandlerContext ctx,
            KineticMessage request) {

        boolean flag = false;

        boolean hasBatchId = request.getCommand().getHeader().hasBatchID();
        if (hasBatchId == false) {
            return false;
        }

        String key = request.getCommand().getHeader().getConnectionID() + SEP
                + request.getCommand().getHeader().getBatchID();

        BatchQueue batchQueue = this.batchMap.get(key);

        MessageType mtype = request.getCommand().getHeader().getMessageType();

        if (batchQueue != null) {

            if (mtype == MessageType.PUT || mtype == MessageType.DELETE) {

                // is added to batch queue
                flag = true;

                // is a batch message
                request.setIsBatchMessage(true);

                // add to batch queue
                batchQueue.add(request);
            }
        } else {
            // there is a batch ID not known at this point
            // the only allowed message type is start message.
            if (mtype != MessageType.START_BATCH) {
                request.setIsInvalidBatchMessage(true);
            }
        }

        return flag;
    }

    private synchronized void processBatchQueue(ChannelHandlerContext ctx,
            KineticMessage km) throws InterruptedException,
            InvalidBatchException {
        
        String key = km.getCommand().getHeader().getConnectionID() + SEP
                + km.getCommand().getHeader().getBatchID();

        BatchQueue batchQueue = this.batchMap.get(key);

        if (batchQueue == null) {
            throw new RuntimeException("No batch Id found for key: " + key);
        }

        try {

            List<KineticMessage> mlist = batchQueue.getMessageList();
            if (mlist.size() > 0) {
                mlist.get(0).setIsFirstBatchMessage(true);
            }

            for (KineticMessage request : batchQueue.getMessageList()) {
                this.processRequest(ctx, request);
            }

        } finally {

            this.batchMap.remove(key);

            /**
             * end batch is called in the end of message processing
             * (simulatorEngine).
             */
        }
    }

    private void processBatchAbort(ChannelHandlerContext ctx, KineticMessage km) {
        String key = km.getCommand().getHeader().getConnectionID() + SEP
                + km.getCommand().getHeader().getBatchID();

        BatchQueue batchQueue = this.batchMap.remove(key);

        if (batchQueue == null) {
            throw new RuntimeException("No batch Id found for key: " + key);
        }

        logger.info("batch aborted ... key=" + key);
    }
}
