/*
  GRANITE DATA SERVICES
  Copyright (C) 2011 GRANITE DATA SERVICES S.A.S.

  This file is part of Granite Data Services.

  Granite Data Services is free software; you can redistribute it and/or modify
  it under the terms of the GNU Library General Public License as published by
  the Free Software Foundation; either version 2 of the License, or (at your
  option) any later version.

  Granite Data Services is distributed in the hope that it will be useful, but
  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
  FITNESS FOR A PARTICULAR PURPOSE. See the GNU Library General Public License
  for more details.

  You should have received a copy of the GNU Library General Public License
  along with this library; if not, see <http://www.gnu.org/licenses/>.
*/

package org.granite.gravity;

import java.io.IOException;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.granite.context.AMFContextImpl;
import org.granite.context.GraniteContext;
import org.granite.logging.Logger;
import org.granite.messaging.webapp.HttpGraniteContext;
import org.granite.util.ContentType;

import flex.messaging.messages.AsyncMessage;

/**
 * @author Franck WOLFF
 */
public abstract class AbstractChannel implements Channel {
    
    ///////////////////////////////////////////////////////////////////////////
    // Fields.

    private static final Logger log = Logger.getLogger(AbstractChannel.class);

    protected final String id;
    protected final String sessionId;
	protected final String clientType;
    protected final Gravity gravity;
    protected final ChannelFactory<? extends Channel> factory;
    // protected final ServletConfig servletConfig;
    
    protected final ConcurrentMap<String, Subscription> subscriptions = new ConcurrentHashMap<String, Subscription>();
    
    protected LinkedList<AsyncPublishedMessage> publishedQueue = new LinkedList<AsyncPublishedMessage>();
    protected final Lock publishedQueueLock = new ReentrantLock();

    protected LinkedList<AsyncMessage> receivedQueue = new LinkedList<AsyncMessage>();
    protected final Lock receivedQueueLock = new ReentrantLock();
    
    protected final AsyncPublisher publisher;
    protected final AsyncReceiver receiver;
    
    ///////////////////////////////////////////////////////////////////////////
    // Constructor.

    protected AbstractChannel(Gravity gravity, String id, ChannelFactory<? extends Channel> factory, String clientType) {        
        if (id == null)
        	throw new NullPointerException("id cannot be null");
        
        this.id = id;
    	GraniteContext graniteContext = GraniteContext.getCurrentInstance();
    	this.clientType = clientType;
    	this.sessionId = graniteContext != null ? graniteContext.getSessionId() : null;
        this.gravity = gravity;
        this.factory = factory;
        
        this.publisher = new AsyncPublisher(this);
        this.receiver = new AsyncReceiver(this);
    }
    
    ///////////////////////////////////////////////////////////////////////////
    // Abstract protected method.
	
	protected abstract boolean hasAsyncHttpContext();	
	protected abstract AsyncHttpContext acquireAsyncHttpContext();
	protected abstract void releaseAsyncHttpContext(AsyncHttpContext context);
    
    ///////////////////////////////////////////////////////////////////////////
    // Channel interface implementation.

	public String getId() {
        return id;
    }
	
	public String getClientType() {
		return clientType;
	}
	
	public ChannelFactory<? extends Channel> getFactory() {
		return factory;
	}
	
	public Gravity getGravity() {
		return gravity;
	}

    public Subscription addSubscription(String destination, String subTopicId, String subscriptionId, boolean noLocal) {
    	Subscription subscription = new Subscription(this, destination, subTopicId, subscriptionId, noLocal);
    	Subscription present = subscriptions.putIfAbsent(subscriptionId, subscription);
    	return (present != null ? present : subscription);
    }

    public Collection<Subscription> getSubscriptions() {
    	return subscriptions.values();
    }
    
    public Subscription removeSubscription(String subscriptionId) {
    	return subscriptions.remove(subscriptionId);
    }

	public void publish(AsyncPublishedMessage message) throws MessagePublishingException {
		if (message == null)
			throw new NullPointerException("message cannot be null");
		
		publishedQueueLock.lock();
		try {
			publishedQueue.add(message);
		}
		finally {
			publishedQueueLock.unlock();
		}

		publisher.queue(getGravity());
	}
	
	public boolean hasPublishedMessage() {
		publishedQueueLock.lock();
		try {
			return !publishedQueue.isEmpty();
		}
		finally {
			publishedQueueLock.unlock();
		}
	}
	
	public boolean runPublish() {
		LinkedList<AsyncPublishedMessage> publishedCopy = null;
		
		publishedQueueLock.lock();
		try {
			if (publishedQueue.isEmpty())
				return false;
			publishedCopy = publishedQueue;
			publishedQueue = new LinkedList<AsyncPublishedMessage>();
		}
		finally {
			publishedQueueLock.unlock();
		}
		
		for (AsyncPublishedMessage message : publishedCopy) {
			try {
				message.publish(this);
			}
			catch (Exception e) {
				log.error(e, "Error while trying to publish message: %s", message);
			}
		}
		
		return true;
	}

	public void receive(AsyncMessage message) throws MessageReceivingException {
		if (message == null)
			throw new NullPointerException("message cannot be null");
		
		Gravity gravity = getGravity();
		
		receivedQueueLock.lock();
		try {
			if (receivedQueue.size() + 1 > gravity.getGravityConfig().getMaxMessagesQueuedPerChannel())
				throw new MessageReceivingException(message, "Could not queue message (channel's queue is full) for channel: " + this);
			
			receivedQueue.add(message);
		}
		finally {
			receivedQueueLock.unlock();
		}

		if (hasAsyncHttpContext())
			receiver.queue(gravity);
	}
	
	public boolean hasReceivedMessage() {
		receivedQueueLock.lock();
		try {
			return !receivedQueue.isEmpty();
		}
		finally {
			receivedQueueLock.unlock();
		}
	}

	public boolean runReceive() {
		return runReceived(null);
	}
	
	protected ObjectOutput newSerializer(GraniteContext context, OutputStream os) {
		return context.getGraniteConfig().newAMF3Serializer(os);
	}
	
	protected String getSerializerContentType() {
		return ContentType.AMF.mimeType();
	}
	
	public boolean runReceived(AsyncHttpContext asyncHttpContext) {
		
		boolean httpAsParam = (asyncHttpContext != null); 
		LinkedList<AsyncMessage> messages = null;
		OutputStream os = null;

		try {
			receivedQueueLock.lock();
			try {
				// Do we have any pending messages? 
				if (receivedQueue.isEmpty())
					return false;
				
				// Do we have a valid http context?
				if (asyncHttpContext == null) {
					asyncHttpContext = acquireAsyncHttpContext();
					if (asyncHttpContext == null)
						return false;
				}
				
				// Both conditions are ok, get all pending messages.
				messages = receivedQueue;
				receivedQueue = new LinkedList<AsyncMessage>();
			}
			finally {
				receivedQueueLock.unlock();
			}
			
			HttpServletRequest request = asyncHttpContext.getRequest();
			HttpServletResponse response = asyncHttpContext.getResponse();
			
			// Set response messages correlation ids to connect request message id.
			String correlationId = asyncHttpContext.getConnectMessage().getMessageId();
			AsyncMessage[] messagesArray = new AsyncMessage[messages.size()];
			int i = 0;
			for (AsyncMessage message : messages) {
				message.setCorrelationId(correlationId);
				messagesArray[i++] = message;
			}
			
			// Setup serialization context (thread local)
			Gravity gravity = getGravity();
	        GraniteContext context = HttpGraniteContext.createThreadIntance(
	            gravity.getGraniteConfig(), gravity.getServicesConfig(),
	            null, request, response
	        );
	        ((AMFContextImpl)context.getAMFContext()).setCurrentAmf3Message(asyncHttpContext.getConnectMessage());
	
	        // Write messages to response output stream.

	        response.setStatus(HttpServletResponse.SC_OK);
	        response.setContentType(getSerializerContentType());
	        response.setDateHeader("Expire", 0L);
	        response.setHeader("Cache-Control", "no-store");
	        
	        os = response.getOutputStream();
	        ObjectOutput serializer = newSerializer(context, os);
	        
	        log.debug("<< [MESSAGES for channel=%s] %s", this, messagesArray);
	        
	        serializer.writeObject(messagesArray);
	        
	        os.flush();
	        response.flushBuffer();
	        
	        return true; // Messages were delivered, http context isn't valid anymore.
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
			// Cleanup serialization context (thread local)
			try {
				GraniteContext.release();
			}
			catch (Exception e) {
				// should never happen...
			}
			
			// Close output stream.
			try {
				if (os != null) {
					try {
						os.close();
					}
					catch (IOException e) {
						log.warn(e, "Could not close output stream (ignored)");
					}
				}
			}
			finally {
				// Cleanup http context (only if this method wasn't explicitly called with a non null
				// AsyncHttpContext from the servlet).
				if (!httpAsParam)
					releaseAsyncHttpContext(asyncHttpContext);
			}
		}
	}

    public void destroy() {
    	Gravity gravity = getGravity();
		gravity.cancel(publisher);
		gravity.cancel(receiver);

    	subscriptions.clear();
	}
    
    ///////////////////////////////////////////////////////////////////////////
    // Protected utilities.
	
	protected boolean queueReceiver() {
		if (hasReceivedMessage()) {
			receiver.queue(getGravity());
			return true;
		}
		return false;
	}	
    
    ///////////////////////////////////////////////////////////////////////////
    // Object overwritten methods.

	@Override
    public boolean equals(Object obj) {
        return (obj instanceof Channel && id.equals(((Channel)obj).getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

	@Override
    public String toString() {
        return getClass().getName() + " {id=" + id + ", subscriptions=" + subscriptions.values() + "}";
    }
}