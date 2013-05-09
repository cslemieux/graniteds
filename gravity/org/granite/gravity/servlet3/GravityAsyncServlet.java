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

package org.granite.gravity.servlet3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.granite.gravity.AbstractGravityServlet;
import org.granite.gravity.AsyncHttpContext;
import org.granite.gravity.Gravity;
import org.granite.gravity.GravityManager;
import org.granite.logging.Logger;
import org.granite.messaging.jmf.JMFDeserializer;
import org.granite.messaging.jmf.JMFSerializer;
import org.granite.messaging.jmf.JMFServletContextListener;
import org.granite.messaging.jmf.SharedContext;
import org.granite.util.ContentType;
import org.granite.util.UUIDUtil;

import flex.messaging.messages.Message;

/**
 * @author Franck WOLFF
 */
public class GravityAsyncServlet extends AbstractGravityServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger log = Logger.getLogger(GravityAsyncServlet.class);
    
    protected SharedContext jmfSharedContext = null;
    
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
        jmfSharedContext = JMFServletContextListener.getSharedContext(config.getServletContext());
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		if (!request.isAsyncSupported())
			throw new ServletException("Asynchronous requests are not supported with this servlet. Please check your web.xml");

		if (request.isAsyncStarted())
			throw new ServletException("Gravity Servlet3 implementation doesn't support dispatch(...) mode");

		Gravity gravity = GravityManager.getGravity(getServletContext());
		AsyncChannelFactory channelFactory = newAsyncChannelFactory(gravity, request.getContentType());
        
		try {
            initializeRequest(gravity, request, response);

            Message[] amf3Requests = deserialize(gravity, request);

            log.debug(">> [AMF3 REQUESTS] %s", (Object)amf3Requests);

            Message[] amf3Responses = null;
            
            boolean accessed = false;
            for (int i = 0; i < amf3Requests.length; i++) {
                Message amf3Request = amf3Requests[i];

                // Ask gravity to create a specific response (will be null for connect request from tunnel).
                Message amf3Response = gravity.handleMessage(channelFactory, amf3Request);
                String channelId = (String)amf3Request.getClientId();
                
                // Mark current channel (if any) as accessed.
                if (!accessed)
                	accessed = gravity.access(channelId);
                
                // (Re)Connect message from tunnel...
                if (amf3Response == null) {
                    if (amf3Requests.length > 1)
                        throw new IllegalArgumentException("Only one connect request is allowed on tunnel.");

                    AsyncChannel channel = gravity.getChannel(channelFactory, channelId);
                    if (channel == null)
                		throw new NullPointerException("No channel on tunnel connect");
                    
                    // Try to send pending messages if any (using current container thread).
                    if (!channel.runReceived(new AsyncHttpContext(request, response, amf3Request))) {
	                    // No pending messages, wait for new ones or timeout.
	                    setConnectMessage(request, amf3Request);
	                	AsyncContext asyncContext = request.startAsync();
	                	asyncContext.setTimeout(getLongPollingTimeout());
	                	try {
	                		asyncContext.addListener(new AsyncRequestListener(channel));
		                	channel.setAsyncContext(asyncContext);
	                	}
	                	catch (Exception e) {
	                		log.error(e, "Error while setting async context. Closing context...");
	                		asyncContext.complete();
	                	}
                    }
                    return;
                }

                if (amf3Responses == null)
                	amf3Responses = new Message[amf3Requests.length];
                amf3Responses[i] = amf3Response;
            }

            log.debug("<< [AMF3 RESPONSES] %s", (Object)amf3Responses);

            serialize(gravity, response, amf3Responses, request.getContentType());
        }
        catch (IOException e) {
            log.error(e, "Gravity message error");
            throw e;
        }
        catch (Exception e) {
            log.error(e, "Gravity message error");
            throw new ServletException(e);
        }
        finally {
        	cleanupRequest(request);
        }
	}


	@Override
	public void destroy() {
		jmfSharedContext = null;

		super.destroy();
	}
	
	protected AsyncChannelFactory newAsyncChannelFactory(Gravity gravity, String contentType) throws ServletException {
		if (ContentType.JMF_AMF.mimeType().equals(contentType)) {
	    	if (jmfSharedContext == null)
	    		throw JMFServletContextListener.newSharedContextNotInitializedException();
			
	    	return new JMFAsyncChannelFactory(gravity, jmfSharedContext);
		}
		return new AsyncChannelFactory(gravity);
	}

	@Override
	protected Message[] deserialize(Gravity gravity, HttpServletRequest request) throws ClassNotFoundException, IOException, ServletException {
		if (ContentType.JMF_AMF.mimeType().equals(request.getContentType())) {
			InputStream is = request.getInputStream();
			try {
				return deserializeJMFAMF(gravity, request, is);
			}
			finally {
				is.close();
			}
		}
		return super.deserialize(gravity, request);
	}

	@Override
	protected Message[] deserialize(Gravity gravity, HttpServletRequest request, InputStream is) throws ClassNotFoundException, IOException, ServletException {
		if (ContentType.JMF_AMF.mimeType().equals(request.getContentType()))
			return deserializeJMFAMF(gravity, request, is);
		return super.deserialize(gravity, request, is);
	}
	
	protected Message[] deserializeJMFAMF(Gravity gravity, HttpServletRequest request, InputStream is) throws ClassNotFoundException, IOException, ServletException {
    	if (jmfSharedContext == null)
    		throw JMFServletContextListener.newSharedContextNotInitializedException();
    	
    	@SuppressWarnings("all") // JDK7 warning (Resource leak: 'deserializer' is never closed)...
		JMFDeserializer deserializer = new JMFDeserializer(is, jmfSharedContext);
        return (Message[])deserializer.readObject();
	}

	protected void serialize(Gravity gravity, HttpServletResponse response, Message[] messages, String contentType) throws IOException, ServletException {
		if (ContentType.JMF_AMF.mimeType().equals(contentType))
			serializeJMFAMF(gravity, response, messages);
		else
			super.serialize(gravity, response, messages);
	}

	protected void serializeJMFAMF(Gravity gravity, HttpServletResponse response, Message[] messages) throws IOException, ServletException {
    	if (jmfSharedContext == null)
    		throw JMFServletContextListener.newSharedContextNotInitializedException();
    	
    	OutputStream os = null;
		try {
            // For SDK 2.0.1_Hotfix2+ (LCDS 2.5+).
			String dsId = null;
            for (Message message : messages) {
	            if ("nil".equals(message.getHeader(Message.DS_ID_HEADER))) {
	            	if (dsId == null)
	            		dsId = UUIDUtil.randomUUID();
	                message.getHeaders().put(Message.DS_ID_HEADER, dsId);
	            }
            }
			
	        response.setStatus(HttpServletResponse.SC_OK);
	        response.setContentType(ContentType.JMF_AMF.mimeType());
	        response.setDateHeader("Expire", 0L);
	        response.setHeader("Cache-Control", "no-store");
	        
	        os = response.getOutputStream();

	        @SuppressWarnings("all") // JDK7 warning (Resource leak: 'serializer' is never closed)...
            JMFSerializer serializer = new JMFSerializer(os, jmfSharedContext);
            serializer.writeObject(messages);
	        
	        os.flush();
	        response.flushBuffer();
		}
		finally {
			if (os != null)
				os.close();
		}
	}
}
