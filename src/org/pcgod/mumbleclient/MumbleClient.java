package org.pcgod.mumbleclient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import net.sf.mumble.MumbleProto.Authenticate;
import net.sf.mumble.MumbleProto.ChannelRemove;
import net.sf.mumble.MumbleProto.ChannelState;
import net.sf.mumble.MumbleProto.ServerSync;
import net.sf.mumble.MumbleProto.UserRemove;
import net.sf.mumble.MumbleProto.UserState;
import net.sf.mumble.MumbleProto.Version;

import org.pcgod.mumbleclient.jni.SWIGTYPE_p_CELTDecoder;
import org.pcgod.mumbleclient.jni.SWIGTYPE_p_CELTMode;
import org.pcgod.mumbleclient.jni.celt;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

public class MumbleClient implements Runnable {
	public enum MessageType {
		ACL, Authenticate, BanList, ChannelRemove, ChannelState, CodecVersion, ContextAction, ContextActionAdd, CryptSetup, PermissionDenied, PermissionQuery, Ping, QueryUsers, Reject, RequestBlob, ServerConfig, ServerSync, TextMessage, UDPTunnel, UserList, UserRemove, UserState, UserStats, Version, VoiceTarget
	}

	public enum UDPMessageType {
		UDPPing, UDPVoiceCELTAlpha, UDPVoiceCELTBeta, UDPVoiceSpeex
	};

	public static final boolean ANDROID = true;;

	public static final int SAMPLE_RATE = 48000;
	public static final int FRAME_SIZE = SAMPLE_RATE / 100;
	public static final String INTENT_CHANNEL_LIST_UPDATE = "mumbleclient.intent.CHANNEL_LIST_UPDATE";
	public static final String INTENT_CURRENT_CHANNEL_CHANGED = "mumbleclient.intent.CURRENT_CHANNEL_CHANGED";
	public static final String INTENT_USER_LIST_UPDATE = "mumbleclient.intent.USER_LIST_UPDATE";
	public static final String LOG_TAG = "mumbleclient";
	private static final int protocolVersion = (1 << 16) | (2 << 8)
			| (3 & 0xFF);
	public ArrayList<Channel> channelArray = new ArrayList<Channel>();
	public int currentChannel = -1;
	public int session;
	public ArrayList<User> userArray = new ArrayList<User>();
	private AudioTrack at;
	private boolean authenticated;
	private SWIGTYPE_p_CELTDecoder celtDecoder;
	private SWIGTYPE_p_CELTMode celtMode;
	private Context ctx;

	private DataInputStream in;
	private DataOutputStream out;
	private Thread pingThread;
	private Socket socket;

	private final String host;
	private final int port;
	private final String username;
	private final String password;

	public MumbleClient(final Context ctx_, final String host_, final int port_,
			final String username_, final String password_) {
		ctx = ctx_;
		host = host_;
		port = port_;
		username = username_;
		password = password_;
	}

	public final boolean isConnected() {
		return socket != null && socket.isConnected();
	}

	public final boolean isSameServer(final String host_, final int port_, final String username_,
			final String password_) {
		return host.equals(host_) && port == port_ && username.equals(username_) && password.equals(password_);
	}

	public final void joinChannel(final int channelId) {
		final UserState.Builder us = UserState.newBuilder();
		us.setSession(session);
		us.setChannelId(channelId);
		try {
			sendMessage(MessageType.UserState, us);
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	public final void run() {
		try {
			final SSLContext ctx_ = SSLContext.getInstance("TLS");
			ctx_.init(null, new TrustManager[] { new LocalSSLTrustManager() },
					null);
			final SSLSocketFactory factory = ctx_.getSocketFactory();
			final SSLSocket socket_ = (SSLSocket) factory.createSocket(host,
					port);
			socket_.setUseClientMode(true);
			socket_.setEnabledProtocols(new String[] { "TLSv1" });
			socket_.startHandshake();

			handleProtocol(socket_);
		} catch (final NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (final KeyManagementException e) {
			e.printStackTrace();
		} catch (final UnknownHostException e) {
			e.printStackTrace();
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	public final void sendMessage(final MessageType t,
			final MessageLite.Builder b) throws IOException {
		final MessageLite m = b.build();
		final short type = (short) t.ordinal();
		final int length = m.getSerializedSize();

		out.writeShort(type);
		out.writeInt(length);
		m.writeTo(out);

		if (t != MessageType.Ping) {
			if (ANDROID) {
				Log.i(LOG_TAG, "<<< " + t);
			} else {
				System.out.println("<<< " + t);
			}
		}
	}

	public final void sendUdpTunnelMessage(final byte[] buffer) throws IOException {
		final short type = (short) MessageType.UDPTunnel.ordinal();
		final int length = buffer.length;

		out.writeShort(type);
		out.writeInt(length);
		out.write(buffer);
	}

	private Channel findChannel(final int id) {
		for (final Channel c : channelArray) {
			if (c.id == id) {
				return c;
			}
		}

		return null;
	}

	private User findUser(final int session_) {
		for (final User u : userArray) {
			if (u.session == session_) {
				return u;
			}
		}

		return null;
	}

	private void handleProtocol(final Socket socket_) throws IOException {
		socket = socket_;
		out = new DataOutputStream(socket_.getOutputStream());
		in = new DataInputStream(socket_.getInputStream());

		final Version.Builder v = Version.newBuilder();
		v.setVersion(protocolVersion);
		v.setRelease("javalib 0.0.1-dev");

		final Authenticate.Builder a = Authenticate.newBuilder();
		a.setUsername(username);
		a.setPassword(password);
		a.addCeltVersions(0x8000000b);

		sendMessage(MessageType.Version, v);
		sendMessage(MessageType.Authenticate, a);

		while (socket_.isConnected()) {
			final short type = in.readShort();
			final int length = in.readInt();
			final byte[] msg = new byte[length];
			in.readFully(msg);
			processMsg(MessageType.class.getEnumConstants()[type], msg);
		}
	}

	@SuppressWarnings("unused")
	private void printChanneList() {
		Log.i(LOG_TAG, "--- begin channel list ---");
		for (Channel c : channelArray) {
			Log.i(LOG_TAG, c.toString());
		}
		Log.i(LOG_TAG, "--- end channel list ---");
	}

	@SuppressWarnings("unused")
	private void printUserList() {
		Log.i(LOG_TAG, "--- begin user list ---");
		for (User u : userArray) {
			Log.i(LOG_TAG, u.toString());
		}
		Log.i(LOG_TAG, "--- end user list ---");
	}

	private void processMsg(final MessageType t, final byte[] buffer)
			throws IOException {
		switch (t) {
		case UDPTunnel:
			processVoicePacket(buffer);
			break;
		case Ping:
			// ignore
			break;
		case ServerSync:
			final ServerSync ss = ServerSync.parseFrom(buffer);
			session = ss.getSession();
			authenticated = true;

			final User user = findUser(session);
			currentChannel = user.channel;
			
			pingThread = new Thread(new PingThread(this), "ping");
			pingThread.start();
			if (ANDROID) {
				Log.i(LOG_TAG, ">>> " + t);
			} else {
				System.out.println(">>> " + t);
			}

			final UserState.Builder usb = UserState.newBuilder();
			usb.setSession(session);
			usb.setPluginContext(ByteString
					.copyFromUtf8("Manual placement\000test"));
			sendMessage(MessageType.UserState, usb);

			if (ANDROID) {
				at = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
						SAMPLE_RATE, AudioFormat.CHANNEL_CONFIGURATION_MONO,
						AudioFormat.ENCODING_PCM_16BIT, 32768,
						AudioTrack.MODE_STREAM);
				at.play();
			}

			celtMode = celt.celt_mode_create(SAMPLE_RATE, FRAME_SIZE);
			celtDecoder = celt.celt_decoder_create(celtMode, 1);

			sendBroadcast(INTENT_CHANNEL_LIST_UPDATE);
			break;
		case ChannelState:
			final ChannelState cs = ChannelState.parseFrom(buffer);
			Channel c = findChannel(cs.getChannelId());
			if (c != null) {
				if (cs.hasName()) {
					c.name = cs.getName();
				}
				sendBroadcast(INTENT_CHANNEL_LIST_UPDATE);
				break;
			}
			// New channel
			c = new Channel();
			c.id = cs.getChannelId();
			c.name = cs.getName();
			channelArray.add(c);
			sendBroadcast(INTENT_CHANNEL_LIST_UPDATE);
			break;
		case ChannelRemove:
			final ChannelRemove cr = ChannelRemove.parseFrom(buffer);
			channelArray.remove(findChannel(cr.getChannelId()));

			sendBroadcast(INTENT_CHANNEL_LIST_UPDATE);
			break;
		case UserState:
			final UserState us = UserState.parseFrom(buffer);
			User u = findUser(us.getSession());
			if (u != null) {
				if (us.hasChannelId()) {
					u.channel = us.getChannelId();
					if (us.getSession() == session) {
						currentChannel = u.channel;
						sendBroadcast(INTENT_CURRENT_CHANNEL_CHANGED);
					}
					sendBroadcast(INTENT_USER_LIST_UPDATE);
				}
				break;
			}
			// New user
			u = new User();
			u.session = us.getSession();
			u.name = us.getName();
			u.channel = us.getChannelId();
			userArray.add(u);

			sendBroadcast(INTENT_USER_LIST_UPDATE);
			break;
		case UserRemove:
			final UserRemove ur = UserRemove.parseFrom(buffer);
			userArray.remove(findUser(ur.getSession()));

			sendBroadcast(INTENT_USER_LIST_UPDATE);
			break;
		default:
			if (ANDROID) {
				Log.i(LOG_TAG, "unhandled message type " + t);
			} else {
				System.out.println("unhandled message type " + t);
			}
		}
	}

	private void processVoicePacket(final byte[] buffer) {
		final UDPMessageType type = UDPMessageType.values()[buffer[0] >> 5 & 0x7];
		// int flags = buffer[0] & 0x1f;

		final ByteBuffer pdsBuffer = ByteBuffer.wrap(buffer);
		pdsBuffer.position(1);
		final PacketDataStream pds = new PacketDataStream(pdsBuffer.slice());
		final long uiSession = pds.readLong();
		final long iSeq = pds.readLong();

//		if (ANDROID)
//			Log.i("mumbleclient", "Type: " + type + " uiSession: " + uiSession
//					+ " iSeq: " + iSeq);
//		else
//			System.out.println("Type: " + type + " uiSession: " + uiSession
//					+ " iSeq: " + iSeq);

		int header = 0;
		int frames = 0;
		final ArrayList<short[]> frameList = new ArrayList<short[]>();
		do {
			header = pds.next();
			if (header > 0) {
				frameList.add(pds.dataBlock(header & 0x7f));
			} else {
				pds.skip(header & 0x7f);
			}

			++frames;
		} while (((header & 0x80) > 0) && pds.isValid());

//		if (ANDROID)
//			Log.i("mumbleclient", "frames: " + frames + " valid: "
//					+ pds.isValid());
//		else
//			System.out
//					.println("frames: " + frames + " valid: " + pds.isValid());

		if (pds.left() > 0) {
			final float x = pds.readFloat();
			final float y = pds.readFloat();
			final float z = pds.readFloat();
			if (ANDROID) {
				Log.i(LOG_TAG, "x: " + x + " y: " + y + " z: " + z);
			} else {
				System.out.println("x: " + x + " y: " + y + " z: " + z);
			}
		}

		final short[] audioOut = new short[FRAME_SIZE];
		for (final short[] frame : frameList) {
			celt.celt_decode(celtDecoder, frame, frame.length, audioOut);
			if (ANDROID) {
				at.write(audioOut, 0, FRAME_SIZE);
			}
		}
	}

	private void recountChannelUsers() {
		for (final Channel c : channelArray) {
			c.userCount = 0;
		}
		
		for (final User u : userArray) {
			final Channel c = findChannel(u.channel);
			c.userCount++;
		}
	}

	private void sendBroadcast(final String action) {
		if (authenticated) {
			recountChannelUsers();
			final Intent i = new Intent(action);
			ctx.sendBroadcast(i);
		}
	}

	@Override
	protected final void finalize() {
		celt.celt_decoder_destroy(celtDecoder);
		celt.celt_mode_destroy(celtMode);
	}
}
