package org.granite.gravity.tomcat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.LinkedList;

import javax.servlet.ServletContext;

import org.apache.catalina.websocket.MessageInbound;
import org.apache.catalina.websocket.StreamInbound;
import org.apache.catalina.websocket.WsOutbound;
import org.granite.context.GraniteContext;
import org.granite.gravity.AbstractChannel;
import org.granite.gravity.AsyncHttpContext;
import org.granite.gravity.Gravity;
import org.granite.gravity.GravityConfig;
import org.granite.logging.Logger;
import org.granite.messaging.webapp.ServletGraniteContext;

import flex.messaging.messages.AsyncMessage;
import flex.messaging.messages.Message;


public class TomcatWebSocketChannel extends AbstractChannel {
	
	private static final Logger log = Logger.getLogger(TomcatWebSocketChannel.class);
	
	private StreamInbound streamInbound = new MessageInboundImpl();
	private ServletContext servletContext;
	private WsOutbound connection;
	private byte[] connectAckMessage;

	
	public TomcatWebSocketChannel(Gravity gravity, String id, TomcatWebSocketChannelFactory factory, ServletContext servletContext, String clientType) {
    	super(gravity, id, factory, clientType);
    	this.servletContext = servletContext;
    }
	
	public void setConnectAckMessage(Message ackMessage) {
		try {
			// Return an acknowledge message with the server-generated clientId
			connectAckMessage = serialize(getGravity(), new Message[] { ackMessage });		        
		}
		catch (IOException e) {
			throw new RuntimeException("Could not send connect acknowledge", e);
		}
	}
	
	public StreamInbound getStreamInbound() {
		return streamInbound;
	}
	
	public class MessageInboundImpl extends MessageInbound {
		
		public MessageInboundImpl() {
		}

		@Override
		protected void onOpen(WsOutbound outbound) {			
			connection = outbound;
			
			log.debug("WebSocket connection onOpen");
			
			if (connectAckMessage == null)
				return;
			
			try {
		        ByteBuffer buf = ByteBuffer.wrap(connectAckMessage);
				connection.writeBinaryMessage(buf);
			}
			catch (IOException e) {
				throw new RuntimeException("Could not send connect acknowledge", e);
			}
			
			connectAckMessage = null;		
		}

		@Override
		public void onClose(int closeCode) {
			log.debug("WebSocket connection onClose %d", closeCode);
			
			connection = null;
		}
		
		@Override
		public void onBinaryMessage(ByteBuffer buf) {
			byte[] data = buf.array();
			
			log.debug("WebSocket connection onBinaryMessage %d", data.length);
			
			try {
				initializeRequest();
				
				Message[] messages = deserialize(getGravity(), data);

	            log.debug(">> [AMF3 REQUESTS] %s", (Object)messages);

	            Message[] responses = null;
	            
	            boolean accessed = false;
	            int responseIndex = 0;
	            for (int i = 0; i < messages.length; i++) {
	                Message message = messages[i];
	                
	                // Ask gravity to create a specific response (will be null with a connect request from tunnel).
	                Message response = getGravity().handleMessage(getFactory(), message);
	                String channelId = (String)message.getClientId();
	                
	                // Mark current channel (if any) as accessed.
	                if (!accessed)
	                	accessed = getGravity().access(channelId);
	                
	                if (response != null) {
		                if (responses == null)
		                	responses = new Message[1];
		                else
		                	responses = Arrays.copyOf(responses, responses.length+1);
		                responses[responseIndex++] = response;
	                }
	            }
	            
	            if (responses != null && responses.length > 0) {
		            log.debug("<< [AMF3 RESPONSES] %s", (Object)responses);
		
		            byte[] resultData = serialize(getGravity(), responses);
		            
		            connection.writeBinaryMessage(ByteBuffer.wrap(resultData));
	            }
			}
			catch (ClassNotFoundException e) {
				log.error(e, "Could not handle incoming message data");
			}
			catch (IOException e) {
				log.error(e, "Could not handle incoming message data");
			}
			finally {
				cleanupRequest();
			}
		}

		@Override
		protected void onTextMessage(CharBuffer buf) throws IOException {
		}
		
		public int getAckLength() {
			return connectAckMessage != null ? connectAckMessage.length : 0;
		}
	}
	
	private Gravity initializeRequest() {
		ServletGraniteContext.createThreadInstance(gravity.getGraniteConfig(), gravity.getServicesConfig(), servletContext, sessionId, clientType);
		return gravity;
	}

	private static Message[] deserialize(Gravity gravity, byte[] data) throws ClassNotFoundException, IOException {
		ByteArrayInputStream is = new ByteArrayInputStream(data);
		try {
			ObjectInput amf3Deserializer = gravity.getGraniteConfig().newAMF3Deserializer(is);
	        Object[] objects = (Object[])amf3Deserializer.readObject();
	        Message[] messages = new Message[objects.length];
	        System.arraycopy(objects, 0, messages, 0, objects.length);
	        
	        return messages;
		}
		finally {
			is.close();
		}
	}
	
	private static byte[] serialize(Gravity gravity, Message[] messages) throws IOException {
		ByteArrayOutputStream os = null;
		try {
	        os = new ByteArrayOutputStream(200*messages.length);
	        ObjectOutput amf3Serializer = gravity.getGraniteConfig().newAMF3Serializer(os);
	        amf3Serializer.writeObject(messages);	        
	        os.flush();
	        return os.toByteArray();
		}
		finally {
			if (os != null)
				os.close();
		}		
	}
	
	private static void cleanupRequest() {
		GraniteContext.release();
	}
	
	@Override
	public boolean runReceived(AsyncHttpContext asyncHttpContext) {
		
		LinkedList<AsyncMessage> messages = null;
		ByteArrayOutputStream os = null;

		try {
			receivedQueueLock.lock();
			try {
				// Do we have any pending messages? 
				if (receivedQueue.isEmpty())
					return false;
				
				// Both conditions are ok, get all pending messages.
				messages = receivedQueue;
				receivedQueue = new LinkedList<AsyncMessage>();
			}
			finally {
				receivedQueueLock.unlock();
			}
			
			if (connection == null)
				return false;
			
			AsyncMessage[] messagesArray = new AsyncMessage[messages.size()];
			int i = 0;
			for (AsyncMessage message : messages)
				messagesArray[i++] = message;
			
			// Setup serialization context (thread local)
			Gravity gravity = getGravity();
	        GraniteContext context = ServletGraniteContext.createThreadInstance(gravity.getGraniteConfig(), gravity.getServicesConfig(), servletContext, sessionId, clientType);
	        
	        os = new ByteArrayOutputStream(500);
	        ObjectOutput amf3Serializer = context.getGraniteConfig().newAMF3Serializer(os);
	        
	        log.debug("<< [MESSAGES for channel=%s] %s", this, messagesArray);
	        
	        amf3Serializer.writeObject(messagesArray);
	        
	        connection.writeBinaryMessage(ByteBuffer.wrap(os.toByteArray()));
	        
	        return true; // Messages were delivered
		}
		catch (IOException e) {
			log.warn(e, "Could not send messages to channel: %s (retrying later)", this);
			
			GravityConfig gravityConfig = getGravity().getGravityConfig();
			if (gravityConfig.isRetryOnError()) {
				receivedQueueLock.lock();
				try {
					if (receivedQueue.size() + messages.size() > gravityConfig.getMaxMessagesQueuedPerChannel()) {
						log.warn(
							"Channel %s has reached its maximum queue capacity %s (throwing %s messages)",
							this,
							gravityConfig.getMaxMessagesQueuedPerChannel(),
							messages.size()
						);
					}
					else
						receivedQueue.addAll(0, messages);
				}
				finally {
					receivedQueueLock.unlock();
				}
			}
			
			return true; // Messages weren't delivered, but http context isn't valid anymore.
		}
		finally {
			if (os != null) {
				try {
					os.close();
				}
				catch (Exception e) {
					// Could not close bytearray ???
				}
			}
			
			// Cleanup serialization context (thread local)
			try {
				GraniteContext.release();
			}
			catch (Exception e) {
				// should never happen...
			}
		}
	}

	@Override
	public void destroy() {
		try {
			super.destroy();
		}
		finally {
			close();
		}
	}
	
	public void close() {
		if (connection != null) {
			try {
				connection.close(1000, ByteBuffer.wrap("Channel closed".getBytes()));
			}
			catch (IOException e) {
				log.error("Could not close WebSocket connection", e);
			}
			connection = null;
		}
	}
	
	@Override
	protected boolean hasAsyncHttpContext() {
		return true;
	}

	@Override
	protected void releaseAsyncHttpContext(AsyncHttpContext context) {
	}

	@Override
	protected AsyncHttpContext acquireAsyncHttpContext() {
    	return null;
    }		
}