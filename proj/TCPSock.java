import java.util.ArrayList;
import java.util.Random;
import java.util.Iterator;

import java.lang.reflect.Method;

/**
 * <p>
 * Title: CPSC 433/533 Programming Assignment
 * </p>
 *
 * <p>
 * Description: Fishnet socket implementation
 * </p>
 *
 * <p>
 * Copyright: Copyright (c) 2006
 * </p>
 *
 * <p>
 * Company: Yale University
 * </p>
 *
 * @author Hao Wang
 * @version 1.0
 */

public class TCPSock {
	// TCP socket states
	enum State {
		// protocol states
		CLOSED, LISTEN, SYN_SENT, ESTABLISHED, SHUTDOWN // close requested, FIN not sent (due to unsent data in queue)
	}

	private final long SYNTimeout = 1000; // resend SYN if timeout

	private final long DATATimeout = 1000; // resend Data if timeout

	private State state;

	public int localPort;
	public int localAddr;
	public int remotePort;
	public int remoteAddr;

	private TCPManager tcpMan;
	private Node node;
	private Manager manager;

	private int startSeq; // random set initially for SYN

	private int nextSeq; // if startSeq == nextSeq, then ready to send

	private int backlog;

	private ArrayList<TCPSock> connQ; // a connection queue for the welcome socket

	private byte window[]; // window

	// both pointers need to mod window.length when accessing window
	// and both pointers are strictly increasing
	// readPointer must always be less than or equal to writePointer
	// but their difference cannot be greater than window.length (otherwise, overflow)
	// available window size equal to: window.length - (writePointer - readPointer)
	private long readPointer; // read from (called by read())

	private long writePointer; // write to (called by onReceive())

	public TCPSock(TCPManager tcpMan, Node node, Manager manager, int localAddr) {
		this.tcpMan = tcpMan;
		this.node = node;
		this.manager = manager;

		this.state = State.CLOSED;

		this.localAddr = localAddr;
		this.localPort = -1; // not set yet
		this.remoteAddr = -1;
		this.remotePort = -1;

		// a buffer (window) for receiving packets
		// the current window is one packet
		this.window = new byte[Transport.MAX_PAYLOAD_SIZE];
		this.readPointer = 0L;
		this.writePointer = 0L;
	}

	// add a timer with a callback function methodName
	private void addTimer(long deltaT, String methodName, String paramTypes[], Object params[]) {
		try {
			Method method = Callback.getMethod(methodName, this, paramTypes);
			Callback cb = new Callback(method, this, params);
			this.manager.addTimer(localAddr, deltaT, cb);
		} catch (Exception e) {
			node.logError("Failed to add timer callback. Method Name: " + methodName + "\nException: " + e);
		}
	}

	/*
	 * The following are the socket APIs of TCP transport service. All APIs are
	 * NON-BLOCKING.
	 */

	/**
	 * Bind a socket to a local port
	 *
	 * @param localPort int local port number to bind the socket to
	 * @return int 0 on success, -1 otherwise
	 */
	public int bind(int localPort) {

		if (this.localPort != -1)
			return -1;

		if (tcpMan.isUsed(localAddr, localPort, remoteAddr, remotePort))
			return -1;

		this.localPort = localPort;
		this.tcpMan.registerSock(this);
		return 0;
	}

	/**
	 * Listen for connections on a socket
	 * 
	 * @param backlog int Maximum number of pending connections
	 * @return int 0 on success, -1 otherwise
	 */
	public int listen(int backlog) {

		if (state != State.CLOSED)
			return -1;

		this.state = State.LISTEN;
		this.backlog = backlog;
		this.connQ = new ArrayList<>();

		return 0;
	}

	/**
	 * Accept a connection on a socket
	 *
	 * @return TCPSock The first established connection on the request queue
	 */
	public TCPSock accept() {

		if (state != State.LISTEN || connQ == null || connQ.size() == 0)
			return null;

		TCPSock connSock = connQ.remove(0);

		tcpMan.registerSock(connSock);

		return connSock;
	}

	public boolean isConnectionPending() {
		return (state == State.SYN_SENT);
	}

	public boolean isClosed() {
		return (state == State.CLOSED);
	}

	public boolean isConnected() {
		return (state == State.ESTABLISHED);
	}

	public boolean isClosurePending() {
		return (state == State.SHUTDOWN);
	}

	/**
	 * Initiate connection to a remote socket
	 *
	 * @param destAddr int Destination node address
	 * @param destPort int Destination port
	 * @return int 0 on success, -1 otherwise
	 */
	public int connect(int destAddr, int destPort) {
		// invalid state
		if (state != State.CLOSED)
			return -1;

		this.remotePort = destPort;
		this.remoteAddr = destAddr;

		// transfer from CLOSED to SYN_SENT
		this.startSeq = new Random().nextInt(1000) + 1; // a random number [1, 1000]
		Transport connRequestPacket = new Transport(localPort, destPort, Transport.SYN, 0, startSeq, new byte[0]);
		byte connRequestByte[] = connRequestPacket.pack();
		Packet packet = new Packet(destAddr, localAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT, node.currentPacketSeq++,
				connRequestByte);
		try {
			manager.sendPkt(localAddr, destAddr, packet.pack());
			System.out.print("S");
			System.out.flush();
		} catch (IllegalArgumentException e) {
			node.logError("Exception: " + e);
		}
		state = State.SYN_SENT;

		// timeout and resend SYN
		this.addTimer(SYNTimeout, "resendSYN", null, null);

		return 0;
	}

	// resendSYN when timeout
	// if the state is no longer SYN_SENT, then do nothing
	public void resendSYN() {
		// invalid state
		if (state != State.SYN_SENT)
			return;

		// transfer from CLOSED to SYN_SENT
		Transport connRequestPacket = new Transport(localPort, remotePort, Transport.SYN, 0, startSeq, new byte[0]);
		byte connRequestByte[] = connRequestPacket.pack();
		Packet packet = new Packet(remoteAddr, localAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
				node.currentPacketSeq++, connRequestByte);
		try {
			manager.sendPkt(localAddr, remoteAddr, packet.pack());
			System.out.print("S");
			System.out.flush();
		} catch (IllegalArgumentException e) {
			node.logError("Exception: " + e);
		}

		// timeout and resend SYN
		this.addTimer(SYNTimeout, "resendSYN", null, null);
	}

	/**
	 * Initiate closure of a connection (graceful shutdown)
	 */
	public void close() {
	}

	/**
	 * Release a connection immediately (abortive shutdown)
	 */
	public void release() {
	}

	/**
	 * Write to the socket up to len bytes from the buffer buf starting at position
	 * pos.
	 *
	 * @param buf byte[] the buffer to write from
	 * @param pos int starting position in buffer
	 * @param len int number of bytes to write
	 * @return int on success, the number of bytes written, which may be smaller
	 *         than len; on failure, -1
	 */
	public int write(byte[] buf, int pos, int len) {
		// not the correct state
		if (state != State.ESTABLISHED)
			return -1;

		// the previous packet hasn't been ACKed yet
		if (startSeq != nextSeq)
			return -1;

		// prepare a payload
		int sendLen = Math.min(len, buf.length - pos);

		sendLen = Math.min(sendLen, Transport.MAX_PAYLOAD_SIZE);

		byte tcpPayload[] = new byte[sendLen];

		for (int i = 0; i < sendLen; i++) {
			tcpPayload[i] = buf[i + pos];
		}

		Transport tcpDataPacket = new Transport(localPort, remotePort, Transport.DATA, 1, startSeq, tcpPayload);

		byte tcpByte[] = tcpDataPacket.pack();
		Packet tcpPacket = new Packet(remoteAddr, localAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
				node.currentPacketSeq++, tcpByte);
		try {
			manager.sendPkt(localAddr, remoteAddr, tcpPacket.pack());
			nextSeq += sendLen;
			System.out.print(".");
			System.out.flush();
		} catch (IllegalArgumentException e) {
			node.logError("Exception: " + e);
		}

		// timeout and resend data
		this.addTimer(DATATimeout, "resendData", new String[] { "[B", "java.lang.Integer", "java.lang.Integer" },
				new Object[] { buf, Integer.valueOf(pos), Integer.valueOf(len) });

		return sendLen;
	}

	public void resendData(byte[] buf, Integer posI, Integer lenI) {
		
		int pos = posI.intValue();
		int len = lenI.intValue();

		// not the correct state
		if (state != State.ESTABLISHED)
			return;

		// has been ACKed
		if (startSeq == nextSeq)
			return;

		// prepare a payload
		int sendLen = Math.min(len, buf.length - pos);

		sendLen = Math.min(sendLen, Transport.MAX_PAYLOAD_SIZE);

		byte tcpPayload[] = new byte[sendLen];

		for (int i = 0; i < sendLen; i++) {
			tcpPayload[i] = buf[i + pos];
		}

		Transport tcpDataPacket = new Transport(localPort, remotePort, Transport.DATA, 1, startSeq, tcpPayload);

		byte tcpByte[] = tcpDataPacket.pack();
		Packet tcpPacket = new Packet(remoteAddr, localAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
				node.currentPacketSeq++, tcpByte);
		try {
			manager.sendPkt(localAddr, remoteAddr, tcpPacket.pack());
			System.out.print("!");
			System.out.flush();
		} catch (IllegalArgumentException e) {
			node.logError("Exception: " + e);
		}

		/// timeout and resend data
		this.addTimer(DATATimeout, "resendData", new String[] { "[B", "java.lang.Integer", "java.lang.Integer" },
				new Object[] { buf, posI, lenI });

	}

	/**
	 * Read from the socket up to len bytes into the buffer buf starting at position
	 * pos.
	 *
	 * @param buf byte[] the buffer
	 * @param pos int starting position in buffer
	 * @param len int number of bytes to read
	 * @return int on success, the number of bytes read, which may be smaller than
	 *         len; on failure, -1
	 */
	public int read(byte[] buf, int pos, int len) {
		// compute read length
		int readLen = Math.min(len, buf.length - pos);
		readLen = Math.min(readLen, (int)(writePointer - readPointer));

		for(int i = 0; i < readLen; i++){
			buf[pos + i] = window[(int)((readPointer++) % (long)window.length)];
		}
		return readLen;
	}

	/*
	 * End of socket API
	 */

	public void onReceive(Packet packet) {

		Transport tcpPacket = Transport.unpack(packet.getPayload());

		int destPort = tcpPacket.getDestPort();
		int srcPort = tcpPacket.getSrcPort();
		int destAddr = packet.getDest();
		int srcAddr = packet.getSrc();

		int type = tcpPacket.getType();
		int seq = tcpPacket.getSeqNum();
		byte packetPayload[] = tcpPacket.getPayload();

		// for SYN packet
		if (type == Transport.SYN) {

			System.out.print("S");
			System.out.flush();

			// if the current socket is not listening or established
			// (An established socket can receive SYN if the first SYN times out
			// and get redirected to the connection socket rather than the welcome socket)
			// or pending connection (include welcome socket) is greater than backlog
			if ((state != State.LISTEN && state != State.ESTABLISHED)
					|| (state == State.LISTEN && connQ.size() >= backlog)) {
				// Send FIN
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

			// Send ACK (there is no need for timeout this packet, as the client who sends
			// SYN will time out)
			// note that availableWindowSize here should be full window for the welcome socket
			Transport connAckPacket = new Transport(destPort, srcPort, Transport.ACK, availableWindowSize(), seq + 1, new byte[0]);
			byte connAckByte[] = connAckPacket.pack();
			Packet ackPacket = new Packet(srcAddr, destAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
					node.currentPacketSeq++, connAckByte);
			try {
				manager.sendPkt(destAddr, srcAddr, ackPacket.pack());
				System.out.print(":"); // ACK for SYN
				System.out.flush();
			} catch (IllegalArgumentException e) {
				node.logError("Exception: " + e);
			}

			// create a connection socket if it does not exist
			// note that it must be a welcome socket
			if (state == State.LISTEN && !isUsedInConnQ(localAddr, localPort, srcAddr, srcPort)
					&& !tcpMan.isUsed(localAddr, localPort, srcAddr, srcPort)) {
				TCPSock connectionSock = new TCPSock(tcpMan, node, manager, localAddr);
				connectionSock.localPort = this.localPort;
				connectionSock.remoteAddr = srcAddr;
				connectionSock.remotePort = srcPort;
				connectionSock.state = State.ESTABLISHED;
				connectionSock.startSeq = seq + 1; // the first expected data seq

				connQ.add(connectionSock); // new socket always appends at the end
			}
			return;
		}

		// for ACK packet
		else if (type == Transport.ACK) {

			// ACK for SYN
			if (state == State.SYN_SENT) {
				if (seq == startSeq + 1) {
					startSeq += 1;
					nextSeq = startSeq;
					state = State.ESTABLISHED;
					System.out.print(":"); // ACK for SYN
					System.out.flush();
					return;
				} else {
					// dangerous! somebody is faking a ACK
					System.out.print("X");
					System.out.flush();
					return;
				}
			}
			// ACK for Data
			else if (state == State.ESTABLISHED) {
				// The ACK expected
				if (seq == nextSeq) {
					startSeq = nextSeq;
					System.out.print(":");
					System.out.flush();
					return;
				}
				// Not the expected ACK
				else {
					System.out.print("?");
					System.out.flush();
					return;
				}
			}
			// ACK for other states
			else {
				System.out.print("X");
				System.out.flush();
				return;
			}
		}

		// For DATA packet
		else if (type == Transport.DATA) {

			// The connection socket is still in connQ, not in tcpMan.sockets
			if (srcAddr != remoteAddr || srcPort != remotePort) {
				// forward to the socket in connQ, if any
				Iterator<TCPSock> iter = connQ.iterator();

				while (iter.hasNext()) {
					TCPSock current = iter.next();
					if (current.localAddr == destAddr && current.localPort == destPort && current.remoteAddr == srcAddr
							&& current.remotePort == srcPort) {
						current.onReceive(packet); // forward to the connection socket
						return;
					}
				}

				// no socket, send fin
				System.out.print("X"); // receive an unexpected packet
				System.out.flush();

				// Send FIN
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

			// The current socket in the correct connection socket
			else {

				// the seq expect
				if (seq == startSeq && availableWindowSize() >= packetPayload.length) {
					System.out.print("."); // receive an expected packet
					System.out.flush();
					startSeq += packetPayload.length;

					// send ACK (no need to time out at the server side)
					Transport connAckPacket = new Transport(destPort, srcPort, Transport.ACK, availableWindowSize(), startSeq, new byte[0]);
					byte connAckByte[] = connAckPacket.pack();
					Packet ackPacket = new Packet(srcAddr, destAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
							node.currentPacketSeq++, connAckByte);
					try {
						manager.sendPkt(destAddr, srcAddr, ackPacket.pack());
						System.out.print(":");
						System.out.flush();
					} catch (IllegalArgumentException e) {
						node.logError("Exception: " + e);
					}

					// note that payload is stored before availableWindowSize is sent in ACK
					// save payload at the socket window
					writeToWindow(packetPayload);

				}
				// out of order packet or window is full
				else {
					System.out.print("!"); // receive an unexpected packet
					System.out.flush();

					// send old ACK (no need to time out at the server side)
					Transport connAckPacket = new Transport(destPort, srcPort, Transport.ACK, availableWindowSize(), startSeq, new byte[0]);
					byte connAckByte[] = connAckPacket.pack();
					Packet ackPacket = new Packet(srcAddr, destAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
							node.currentPacketSeq++, connAckByte);
					try {
						manager.sendPkt(destAddr, srcAddr, ackPacket.pack());
						System.out.print("?");
						System.out.flush();
					} catch (IllegalArgumentException e) {
						node.logError("Exception: " + e);
					}
				}
			}
		}

	}

	// Test whether the same setting is used by other sockets currently in connQ
	public boolean isUsedInConnQ(int localAddr, int localPort, int remoteAddr, int remotePort) {
		Iterator<TCPSock> iter = connQ.iterator();

		while (iter.hasNext()) {
			TCPSock current = iter.next();
			if (current.localAddr == localAddr && current.localPort == localPort && current.remoteAddr == remoteAddr
					&& current.remotePort == remotePort)
				return true;
		}

		return false;
	}

	/* all functions related to window and read/write pointers */

	// available window size
	private int availableWindowSize() {
		return (int)((long)window.length - (writePointer - readPointer));
	}

	// write to window (must be called after checking availableWindowSize)
	private void writeToWindow(byte payload[]) {
		for(int i = 0; i < payload.length; i++) {
			window[(int)((writePointer++) % (long)window.length)] = payload[i];
		}
	}
}
