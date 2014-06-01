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
package com.seagate.kinetic.simulator.io.provider.nio.ssl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.seagate.kinetic.common.lib.KineticMessage;
import com.seagate.kinetic.simulator.io.provider.nio.NioQueuedRequestProcessRunner;
import com.seagate.kinetic.simulator.io.provider.nio.RequestProcessRunner;
import com.seagate.kinetic.simulator.io.provider.spi.MessageService;

/**
 *
 * @author chiaming
 *
 */
public class SslMessageServiceHandler extends
		SimpleChannelInboundHandler<KineticMessage> {

	private static final Logger logger = Logger
			.getLogger(SslMessageServiceHandler.class.getName());

	private MessageService lcservice = null;

	private boolean enforceOrdering = false;

	private NioQueuedRequestProcessRunner queuedRequestProcessRunner = null;

	public SslMessageServiceHandler(MessageService lcservice2) {
		this.lcservice = lcservice2;

		this.enforceOrdering = lcservice.getServiceConfiguration()
				.getMessageOrderingEnforced();

		if (this.enforceOrdering) {
			this.queuedRequestProcessRunner = new NioQueuedRequestProcessRunner(
					lcservice);
		}
	}

	@Override
	public void channelActive(final ChannelHandlerContext ctx) throws Exception {
		logger.fine("Kinetic ssl channel is active ...");
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx,
			KineticMessage request)
			throws Exception {

		if (enforceOrdering) {
			// process request sequentially
			queuedRequestProcessRunner.processRequest(ctx, request);
		} else {
			RequestProcessRunner rpr = new RequestProcessRunner(lcservice, ctx,
					request);
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

		if (this.queuedRequestProcessRunner != null) {
			logger.info("removing/closing ssl nio queued request process runner ...");
			this.queuedRequestProcessRunner.close();
		}
	}

}