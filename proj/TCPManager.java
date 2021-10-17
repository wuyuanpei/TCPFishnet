
/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet TCP manager</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */

import java.util.ArrayList;
import java.util.Iterator;

public class TCPManager {
	private Node node;
	private int addr;
	private Manager manager;

	// private static final byte dummy[] = new byte[0];

	private ArrayList<TCPSock> sockets;

	public TCPManager(Node node, int addr, Manager manager) {
		this.node = node;
		this.addr = addr;
		this.manager = manager;
		sockets = new ArrayList<>();
	}

	/**
	 * Start this TCP manager
	 */
	public void start() {

	}

	/*
	 * Begin socket API
	 */

	/**
	 * Create a socket
	 *
	 * @return TCPSock the newly created socket, which is not yet bound to a local
	 *         port
	 */
	public TCPSock socket() {
		return new TCPSock(this, node, manager, addr);
	}

	// Find the socket that has the best match
	public TCPSock findBestMatch(int destAddr, int destPort, int srcAddr, int srcPort) {
		
		Iterator<TCPSock> iter = sockets.iterator();
		
		// First, try to find the exact match
		while(iter.hasNext()) {
			TCPSock current = iter.next();
			if(current.localAddr == destAddr &&
				current.localPort == destPort && 
				current.remoteAddr == srcAddr &&
				current.remotePort == srcPort)
				return current;
		}
		
		// Second, try to find welcome socket (i.e., destX both are -1)
		Iterator<TCPSock> iter2 = sockets.iterator();
		while(iter2.hasNext()) {
			TCPSock current = iter2.next();
			if(current.localAddr == destAddr &&
				current.localPort == destPort && 
				current.remoteAddr == -1 &&
				current.remotePort == -1)
				return current;
		}

		// Finally, return null
		return null;
	}
	
	// isUsed and registerSock are used by bind in TCPSock
	// Test whether the same setting is used by other sockets currently
	public boolean isUsed(int localAddr, int localPort, int remoteAddr, int remotePort) {
		Iterator<TCPSock> iter = sockets.iterator();
		
		while(iter.hasNext()) {
			TCPSock current = iter.next();
			if(current.localAddr == localAddr &&
				current.localPort == localPort && 
				current.remoteAddr == remoteAddr &&
				current.remotePort == remotePort)
				return true;
		}
		
		return false;
	}

	// Append the new socket at the end
	public void registerSock(TCPSock tcpSock) {
		sockets.add(tcpSock);
	}

	// When the manager receives a TCP packet
	public void onReceive(Packet packet) {

		Transport tcpPacket = Transport.unpack(packet.getPayload());

		int destPort = tcpPacket.getDestPort();
		int srcPort = tcpPacket.getSrcPort();
		int destAddr = packet.getDest();
		int srcAddr = packet.getSrc();

		// the responsible socket: maybe a welcome socket, maybe a connection socket
		TCPSock bestSock = findBestMatch(destAddr, destPort, srcAddr, srcPort);

		// there is no corresponding socket
		if (bestSock == null) {

			// receive an unexpected packet
			System.out.print("X");
			System.out.flush();

			// send FIN, indicating connection refused
			Transport connRefusePacket = new Transport(destPort, srcPort, Transport.FIN, 0, 0, new byte[0]);
			byte connRefuseByte[] = connRefusePacket.pack();
			Packet finPacket = new Packet(srcAddr, destAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
					node.currentPacketSeq++, connRefuseByte);
			try {
				manager.sendPkt(destAddr, srcAddr, finPacket.pack());
				System.out.print("F");
				System.out.flush();
			} catch (IllegalArgumentException e) {
				node.logError("Exception: " + e);
			}
			return;
		}
		
		// The responsible socket
		bestSock.onReceive(packet);

	}

	/*
	 * End Socket API
	 */
}
