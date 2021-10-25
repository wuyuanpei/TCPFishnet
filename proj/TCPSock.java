import java.util.ArrayList;
import java.util.HashMap;
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
		CLOSED, LISTEN, SYN_SENT, ESTABLISHED, 
		SHUTDOWN // close requested, FIN not sent (due to unsent data in queue); or receive FIN, but read window is not empty
	}

	private final long SYNTimeout = 1000; // resend SYN if timeout

	private long DATATimeout = 1000; // resend Data if timeout. This field is calculated by estRTT and devRTT

	// Estimated RTT to set up DATATimeout
	private long estRTT = -1;
	private long devRTT = -1;
	private final double alpha = 0.125;
	private final double beta = 0.25;
	// store the sample RTTs, key is the ACK seq (i.e., nextSeq), value is the data
	// sent time
	private HashMap<Integer, Long> sampleRTTs;

	private final long RECEIVETimeout = 60000; // nothing to receive for this amount of time, then release

	private long receiveTime;

	private State state;

	public int localPort;
	public int localAddr;
	public int remotePort;
	public int remoteAddr;

	private TCPManager tcpMan;
	private Node node;
	private Manager manager;

	private int baseSeq; // random set initially for SYN

	private int sendSeq;

	private ArrayList<Integer> seqNumbers;

	private int backlog;

	private ArrayList<TCPSock> connQ; // a connection queue for the welcome socket

	private byte readWindow[]; // window for read (at the server side)

	private final int BUFFER_SIZE = 32; // The size for readWindow and writeWindow
	// In the unit of Transport.MAX_PAYLOAD_SIZE

	// both pointers need to mod window.length when accessing window
	// and both pointers are strictly increasing
	// readPointer must always be less than or equal to writePointer
	// but their difference cannot be greater than window.length (otherwise,
	// overflow)
	// available window size equal to: window.length - (writePointer - readPointer)
	private long readPointer; // read from (called by read())
	private long writePointer; // write to (called by onReceive())

	private byte writeWindow[]; // window for write (at the client side)
	// invariance:
	// writeWPointer >= readWPointer >= readSafeWPointer
	// (writeWPointer - readSafeWPointer) <= writeWindow.length
	private long readWPointer; // always points to the first byte that has not been sent (the first time)
	private long writeWPointer; // always points to the first byte that write() is going to write
	private long readSafeWPointer; // always points to the first byte that has not been ACKed yet

	private int cwnd = 16;
	private int ssthresh = 64 * 1024;

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
		this.readWindow = new byte[BUFFER_SIZE * Transport.MAX_PAYLOAD_SIZE];
		this.readPointer = 0L;
		this.writePointer = 0L;

		this.writeWindow = new byte[BUFFER_SIZE * Transport.MAX_PAYLOAD_SIZE];
		this.readWPointer = 0L;
		this.writeWPointer = 0L;
		this.readSafeWPointer = 0L;

		// used to store sample RTTs to estimate RTT and calculate time out
		this.sampleRTTs = new HashMap<>();

		this.seqNumbers = new ArrayList<>(); // The sequence of execpted seq numbers that will be ACKed
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
		this.baseSeq = new Random().nextInt(1000) + 1; // a random number [1, 1000]
		Transport connRequestPacket = new Transport(localPort, destPort, Transport.SYN, 0, baseSeq, new byte[0]);
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
		Transport connRequestPacket = new Transport(localPort, remotePort, Transport.SYN, 0, baseSeq, new byte[0]);
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

		// for welcome socket
		if (remoteAddr == -1 || remotePort == -1) {
			release();
			return;
		}

		// for connection socket

		// client side: no packet that needs resend
		// server side: always
		if (readSafeWPointer == writeWPointer && seqNumbers.isEmpty()) {
			// send FIN and shutdown
			// Send FIN
			Transport connRefusePacket = new Transport(localPort, remotePort, Transport.FIN, 0, 0, new byte[0]);
			byte connRefuseByte[] = connRefusePacket.pack();
			Packet finPacket = new Packet(remoteAddr, localAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
					node.currentPacketSeq++, connRefuseByte);
			try {
				manager.sendPkt(localAddr, remoteAddr, finPacket.pack());
				System.out.print("F");
			} catch (IllegalArgumentException e) {
				node.logError("Exception: " + e);
			}
			release();
			return;

		}
		// some packet may still need resend
		else {
			state = State.SHUTDOWN;
			return;
		}

	}

	/**
	 * Release a connection immediately (abortive shutdown)
	 */
	public void release() {
		tcpMan.unregisterSock(this);
		state = State.CLOSED;
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

		/*
		 * // the previous packet hasn't been ACKed yet if (startSeq != nextSeq) return
		 * 0;
		 */

		int writeLen = Math.min(len, buf.length - pos);

		writeLen = Math.min(writeLen, availableWWindowSize());

		// Do nothing
		if (writeLen == 0)
			return 0;

		writeToWWindow(buf, pos, writeLen);

		tryToSend();

		return writeLen;
	}

	// try to send packets
	public void tryToSend() {

		boolean sendSomething = false;

		while (cwnd > 0) {

			int sendPktLen = Math.min(Transport.MAX_PAYLOAD_SIZE, contentLengthWWindow());

			if(sendPktLen == 0) break; // nothing to send

			sendSomething = true;

			byte tcpPayload[] = new byte[sendPktLen];
			
			readFromWWindow(tcpPayload, readWPointer);

			readWPointer += sendPktLen;

			Transport tcpDataPacket = new Transport(localPort, remotePort, Transport.DATA, 1, sendSeq, tcpPayload);

			byte tcpByte[] = tcpDataPacket.pack();
			Packet tcpPacket = new Packet(remoteAddr, localAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
					node.currentPacketSeq++, tcpByte);
			try {
				manager.sendPkt(localAddr, remoteAddr, tcpPacket.pack());
				sendSeq += sendPktLen;
				seqNumbers.add(sendSeq);
				cwnd--;
				sampleRTTs.put(sendSeq, manager.now());
				System.out.print(".");
				System.out.flush();
			} catch (IllegalArgumentException e) {
				node.logError("Exception: " + e);
			}
		}

		printSeqNumbers();

		// timeout and resend data
		debug("DATATimeout:" + DATATimeout);
		// At least one packet is sent
		if(sendSomething)
			this.addTimer(DATATimeout, "resendData", new String[] {"java.lang.Integer"}, new Object[] {Integer.valueOf(sendSeq)});
	}

	
	// resend all packets that has not been ACKed
	public void resendData(Integer lastSendSeqI) {

		// not the correct state
		if (state != State.ESTABLISHED && state != State.SHUTDOWN)
			return;

		// has been ACKed
		if (!seqNumbers.contains(lastSendSeqI))
			return;

		int resendSeq = baseSeq;

		debug("lastSendSeqI=" + lastSendSeqI.intValue() + " resendSeq=" +resendSeq);

		printSeqNumbers();
		
		long tmpPointer = readSafeWPointer;
		
		for(int i = 0; i < seqNumbers.size(); i++) {

			int sendPktLen = seqNumbers.get(i) - resendSeq;

			byte tcpPayload[] = new byte[sendPktLen];

			readFromWWindow(tcpPayload, tmpPointer);

			tmpPointer += sendPktLen;

			Transport tcpDataPacket = new Transport(localPort, remotePort, Transport.DATA, 1, resendSeq, tcpPayload);

			byte tcpByte[] = tcpDataPacket.pack();
			Packet tcpPacket = new Packet(remoteAddr, localAddr, Packet.MAX_TTL, Protocol.TRANSPORT_PKT,
					node.currentPacketSeq++, tcpByte);
			try {
				manager.sendPkt(localAddr, remoteAddr, tcpPacket.pack());
				resendSeq += sendPktLen;
				System.out.print("!");
				System.out.flush();
			} catch (IllegalArgumentException e) {
				node.logError("Exception: " + e);
			}
		}
		// timeout and resend data
		debug("DATAResendTimeout:" + DATATimeout);
		this.addTimer(DATATimeout, "resendData", new String[] {"java.lang.Integer"}, new Object[] {lastSendSeqI});

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
		readLen = Math.min(readLen, (int) (writePointer - readPointer));

		for (int i = 0; i < readLen; i++) {
			buf[pos + i] = readWindow[(int) ((readPointer++) % (long) readWindow.length)];
		}

		if(state == State.SHUTDOWN && writePointer == readPointer) {
			release();
		}
		return readLen;
	}

	/*
	 * End of socket API
	 */

	// If a socket hasn't receive any package for RECEIVETimeout time, then release
	public void releaseIfNoReceive() {
		long timeNow = manager.now();
		if (receiveTime + RECEIVETimeout <= timeNow) {
			release();
		}
	}

	public void onReceive(Packet packet) {

		// For connection socket, close if haven't receive anything for a long time
		if (remoteAddr != -1 && remotePort != -1) {
			receiveTime = manager.now();
			addTimer(RECEIVETimeout, "releaseIfNoReceive", null, null);
		}

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
			// note that availableWindowSize here should be full window for the welcome
			// socket
			Transport connAckPacket = new Transport(destPort, srcPort, Transport.ACK, availableWindowSize(), seq + 1,
					new byte[0]);
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
				connectionSock.baseSeq = seq + 1; // the first expected data seq
				//connectionSock.sendSeq = seq + 1; // For the server, nextSeq is always equal to startSeq

				connQ.add(connectionSock); // new socket always appends at the end
			}
			return;
		}

		// for ACK packet
		else if (type == Transport.ACK) {

			// ACK for SYN
			if (state == State.SYN_SENT) {
				if (seq == baseSeq + 1) {
					baseSeq += 1;
					sendSeq = baseSeq;
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
				if (seqNumbers.size() > 0 && seq == seqNumbers.get(0)) {
					seqNumbers.remove(0);
					debug("receive ACK:"+seq);
					printSeqNumbers();
					readSafeWPointer += seq - baseSeq; // increment by the length of the packet
					baseSeq = seq;
					System.out.print(":");
					System.out.flush();

					cwnd++;

					// update estRTT and devRTT and DATATimeout
					long sentTime = sampleRTTs.remove(seq).longValue();
					long sampleRTT = manager.now() - sentTime;

					// for the first measure
					if (estRTT == -1) {
						estRTT = sampleRTT;
						devRTT = sampleRTT;
						DATATimeout = estRTT + 4 * devRTT;
					}
					else {
						estRTT = (long) ((1.0 - alpha) * ((double) estRTT) + alpha * (double) sampleRTT);
						devRTT = (long) ((1.0 - beta) * ((double) devRTT) + beta * (double) Math.abs(sampleRTT - estRTT));
						DATATimeout = estRTT + 4 * devRTT;
					}

					tryToSend();

					return;
				}
				// Not the expected ACK
				else {
					System.out.print("?");
					System.out.flush();
					return;
				}
			}
			// ACK for SHUTDOWN
			else if (state == State.SHUTDOWN) {
				// The ACK expected
				if (seqNumbers.size() > 0 && seq == seqNumbers.get(0)) {
					seqNumbers.remove(0);
					debug("receive ACK:"+seq);
					printSeqNumbers();
					readSafeWPointer += seq - baseSeq; // increment by the length of the packet
					baseSeq = seq;
					System.out.print(":");
					System.out.flush();

					// update estRTT and devRTT and DATATimeout
					long sentTime = sampleRTTs.remove(seq).longValue();
					long sampleRTT = manager.now() - sentTime;

					// for the first measure
					if (estRTT == -1) {
						estRTT = sampleRTT;
						devRTT = sampleRTT;
						DATATimeout = estRTT + 4 * devRTT;
					}
					else {
						estRTT = (long) ((1.0 - alpha) * ((double) estRTT) + alpha * (double) sampleRTT);
						devRTT = (long) ((1.0 - beta) * ((double) devRTT) + beta * (double) Math.abs(sampleRTT - estRTT));
						DATATimeout = estRTT + 4 * devRTT;
					}

					close(); // call close again
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
				if (seq == baseSeq && availableWindowSize() >= packetPayload.length) {
					System.out.print("."); // receive an expected packet
					System.out.flush();
					baseSeq += packetPayload.length;
					debug("receive:" + seq);

					// send ACK (no need to time out at the server side)
					Transport connAckPacket = new Transport(destPort, srcPort, Transport.ACK, availableWindowSize() - packetPayload.length,
							baseSeq, new byte[0]);
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

					// save payload at the socket window
					writeToWindow(packetPayload);

					return;
				}
				// out of order packet or window is full
				else {
					System.out.print("!"); // receive an unexpected packet
					System.out.flush();

					// send old ACK (no need to time out at the server side)
					Transport connAckPacket = new Transport(destPort, srcPort, Transport.ACK, availableWindowSize(),
							baseSeq, new byte[0]);
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
					return;
				}
			}
		}

		// For FIN packet
		else if (type == Transport.FIN) {
			// simply release itself
			System.out.print("F");
			if(readPointer != writePointer) { // still need to be read
				state = State.SHUTDOWN;
			} else {
				release();
			}
			return;
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
		return (int) ((long) readWindow.length - (writePointer - readPointer));
	}

	// write to window (must be called after checking availableWindowSize)
	private void writeToWindow(byte payload[]) {
		for (int i = 0; i < payload.length; i++) {
			readWindow[(int) ((writePointer++) % (long) readWindow.length)] = payload[i];
		}
	}

	// available write window size
	private int availableWWindowSize() {
		return (int) ((long) writeWindow.length - (writeWPointer - readSafeWPointer));
	}

	// write to Wwindow (must be called after checking availableWWindowSize)
	private void writeToWWindow(byte payload[], int pos, int size) {
		for (int i = 0; i < size; i++) {
			writeWindow[(int) ((writeWPointer++) % (long) writeWindow.length)] = payload[pos + i];
		}
	}

	// write window content length
	private int contentLengthWWindow() {
		return (int) (writeWPointer - readWPointer);
	}

	// readFrom Wwindow for payload.length bytes (must be called after checking
	// contentLengthWWindow to avoid payload.length to be too large)
	private void readFromWWindow(byte payload[], long pointer) {
		for (int i = 0; i < payload.length; i++) {
			payload[i] = writeWindow[(int) ((pointer++) % (long) writeWindow.length)];
		}
	}

	// For debug

	public static final boolean _DEBUG = false;

	// for debug purpose
	public void printSeqNumbers() {

		if(!_DEBUG) return;

		System.out.print("{");
		for(int i = 0; i < seqNumbers.size(); i++) {
			System.out.print(seqNumbers.get(i));
			if(i != seqNumbers.size() - 1) {
				System.out.print(",");
			}
		}
		System.out.println("}");
		
	}

	public void debug(String s) {
		if(_DEBUG)
			System.out.println(s);
	}
}
