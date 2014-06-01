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
package com.seagate.kinetic.simulator.utility;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import kinetic.simulator.SimulatorConfiguration;

import com.google.protobuf.ByteString;
import com.seagate.kinetic.proto.Kinetic.Message.GetLog.Configuration;
import com.seagate.kinetic.proto.Kinetic.Message.GetLog.Configuration.Interface;

public abstract class ConfigurationUtil {

    private final static Logger logger = Logger
            .getLogger(ConfigurationUtil.class.getName());
    private final static String VENDER = "Seagate";
    private final static String MODEL = "Simulator";
    private final static byte[] SERIAL_NUMBER = "93C3DAFD-C894-3C88-A4B0-632A90D2A04B"
            .getBytes(Charset.forName("UTF-8"));
    private final static String COMPILATION_DATE = new Date().toString();
    private final static String PROTOCOL_COMPILATION_DATE = new Date()
            .toString();

    @SuppressWarnings("static-access")
    public static Configuration getConfiguration(SimulatorConfiguration config)
            throws UnknownHostException {
        Configuration.Builder configuration = Configuration.newBuilder();
        configuration.setVendor(VENDER);
        configuration.setModel(MODEL);
        configuration.setSerialNumber(ByteString.copyFrom(SERIAL_NUMBER));
        configuration.setCompilationDate(COMPILATION_DATE);
        configuration.setProtocolCompilationDate(PROTOCOL_COMPILATION_DATE);

        List<Interface> interfaces = new ArrayList<Interface>();
        Interface.Builder itf1 = null;

        try {

            Enumeration<NetworkInterface> netInterfaces = NetworkInterface
                    .getNetworkInterfaces();

            while (netInterfaces.hasMoreElements()) {

                // get next interface
                NetworkInterface ni = netInterfaces.nextElement();

                itf1 = Interface.newBuilder();
                itf1.setName(ni.getDisplayName());

                // set mac addr
                byte[] mac = ni.getHardwareAddress();
                if (mac != null) {
                    String macS = bytesToStringMac(mac);
                    itf1.setMAC(ByteString.copyFromUtf8(macS));
                }

                // get inet addresses on this interface
                Enumeration<InetAddress> addresses = ni.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    // get next inet addr
                    InetAddress addr2 = addresses.nextElement();
                    // get string address
                    String addrString = addr2.getHostAddress();

                    if (addr2 instanceof Inet6Address) {
                        itf1.setIpv6Address(ByteString.copyFromUtf8(addrString));
                    } else {
                        itf1.setIpv4Address(ByteString.copyFromUtf8(addrString));
                    }
                }

                interfaces.add(itf1.build());
            }

        } catch (SocketException e) {
            logger.log(Level.SEVERE, "Can not get the network Interface");
        }

        for (Interface tempItf : interfaces) {
            configuration.addInterface(tempItf);
        }

        configuration.setPort(config.getPort());
        configuration.setTlsPort(config.getSslPort());

        if (null != config.getSimulatorVersion()) {
            configuration.setVersion(config.getSimulatorVersion());
        }

        if (null != config.getSimulatorSourceHash()) {
            configuration.setSourceHash(config.getSimulatorSourceHash());
        }

        if (null != config.getProtocolVersion()) {
            configuration.setProtocolVersion(config.getProtocolVersion());
        }

        if (null != config.getProtocolSourceHash()) {
            configuration.setProtocolSourceHash(config.getProtocolSourceHash());
        }

        return configuration.build();
    }

    private static String bytesToStringMac(byte[] mac) {
        StringBuilder sb = new StringBuilder(18);
        for (byte b : mac) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }
}