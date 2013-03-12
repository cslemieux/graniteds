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

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.ObjectName;

import org.granite.clustering.DistributedData;
import org.granite.config.GraniteConfig;
import org.granite.config.flex.Destination;
import org.granite.config.flex.ServicesConfig;
import org.granite.context.GraniteContext;
import org.granite.context.SimpleGraniteContext;
import org.granite.gravity.adapters.AdapterFactory;
import org.granite.gravity.adapters.ServiceAdapter;
import org.granite.gravity.security.GravityDestinationSecurizer;
import org.granite.gravity.security.GravityInvocationContext;
import org.granite.jmx.MBeanServerLocator;
import org.granite.jmx.OpenMBean;
import org.granite.logging.Logger;
import org.granite.messaging.amf.process.AMF3MessageInterceptor;
import org.granite.messaging.service.security.SecurityService;
import org.granite.messaging.service.security.SecurityServiceException;
import org.granite.util.TypeUtil;
import org.granite.util.UUIDUtil;

import flex.messaging.messages.AcknowledgeMessage;
import flex.messaging.messages.AsyncMessage;
import flex.messaging.messages.CommandMessage;
import flex.messaging.messages.ErrorMessage;
import flex.messaging.messages.Message;

/**
 * @author William DRAI
 * @author Franck WOLFF
 */
public class DefaultGravity implements Gravity, DefaultGravityMBean {

    ///////////////////////////////////////////////////////////////////////////
    // Fields.

    private static final Logger log = Logger.getLogger(Gravity.class);

    private final Map<String, Object> applicationMap = new HashMap<String, Object>();
    private final ConcurrentHashMap<String, TimeChannel<?>> channels = new ConcurrentHashMap<String, TimeChannel<?>>();
    
    private GravityConfig gravityConfig = null;
    private ServicesConfig servicesConfig = null;
    private GraniteConfig graniteConfig = null;

    private Channel serverChannel = null;
    private AdapterFactory adapterFactory = null;
    private GravityPool gravityPool = null;

    private Timer channelsTimer;
    private boolean started;

    ///////////////////////////////////////////////////////////////////////////
    // Constructor.

    public DefaultGravity(GravityConfig gravityConfig, ServicesConfig servicesConfig, GraniteConfig graniteConfig) {
        if (gravityConfig == null || servicesConfig == null || graniteConfig == null)
            throw new NullPointerException("All arguments must be non null.");

        this.gravityConfig = gravityConfig;
        this.servicesConfig = servicesConfig;
        this.graniteConfig = graniteConfig;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Properties.

    public GravityConfig getGravityConfig() {
		return gravityConfig;
	}

    public ServicesConfig getServicesConfig() {
        return servicesConfig;
    }

	public GraniteConfig getGraniteConfig() {
        return graniteConfig;
    }

	public boolean isStarted() {
        return started;
    }
	
	public ServiceAdapter getServiceAdapter(String messageType, String destinationId) {
		return adapterFactory.getServiceAdapter(messageType, destinationId);
	}

    ///////////////////////////////////////////////////////////////////////////
    // Starting/stopping.

    public void start() throws Exception {
    	log.info("Starting Gravity...");
        synchronized (this) {
        	if (!started) {
	            adapterFactory = new AdapterFactory(this);
	            internalStart();
	            serverChannel = new ServerChannel(this, ServerChannel.class.getName(), null, null);
	            started = true;
        	}
        }
    	log.info("Gravity successfully started.");
    }
    
    protected void internalStart() {
        gravityPool = new GravityPool(gravityConfig);
        channelsTimer = new Timer();
        
        if (graniteConfig.isRegisterMBeans()) {
	        try {
	            ObjectName name = new ObjectName("org.graniteds:type=Gravity,context=" + graniteConfig.getMBeanContextName());
		        log.info("Registering MBean: %s", name);
	            OpenMBean mBean = OpenMBean.createMBean(this);
	        	MBeanServerLocator.getInstance().register(mBean, name, true);
	        }
	        catch (Exception e) {
	        	log.error(e, "Could not register Gravity MBean for context: %s", graniteConfig.getMBeanContextName());
	        }
        }
    }
    
    public void restart() throws Exception {
    	synchronized (this) {
    		stop();
    		start();
    	}
	}

	public void reconfigure(GravityConfig gravityConfig, GraniteConfig graniteConfig) {
    	this.gravityConfig = gravityConfig;
    	this.graniteConfig = graniteConfig;
    	if (gravityPool != null)
    		gravityPool.reconfigure(gravityConfig);
    }

    public void stop() throws Exception {
        stop(true);
    }

    public void stop(boolean now) throws Exception {
    	log.info("Stopping Gravity (now=%s)...", now);
        synchronized (this) {
        	if (adapterFactory != null) {
	            try {
					adapterFactory.stopAll();
				} catch (Exception e) {
        			log.error(e, "Error while stopping adapter factory");
				}
	            adapterFactory = null;
        	}

        	if (serverChannel != null) {
	            try {
					removeChannel(serverChannel.getId());
				} catch (Exception e) {
        			log.error(e, "Error while removing server channel: %s", serverChannel);
				}
	            serverChannel = null;
        	}
            
            if (channelsTimer != null) {
	            try {
					channelsTimer.cancel();
				} catch (Exception e) {
        			log.error(e, "Error while cancelling channels timer");
				}
	            channelsTimer = null;
            }
        	
        	if (gravityPool != null) {
        		try {
	        		if (now)
	        			gravityPool.shutdownNow();
	        		else
	        			gravityPool.shutdown();
        		}
        		catch (Exception e) {
        			log.error(e, "Error while stopping thread pool");
        		}
        		gravityPool = null;
        	}
            
            started = false;
        }
        log.info("Gravity sucessfully stopped.");
    }

    ///////////////////////////////////////////////////////////////////////////
    // GravityMBean attributes implementation.

	public String getGravityFactoryName() {
		return gravityConfig.getGravityFactory();
	}

	public long getChannelIdleTimeoutMillis() {
		return gravityConfig.getChannelIdleTimeoutMillis();
	}
	public void setChannelIdleTimeoutMillis(long channelIdleTimeoutMillis) {
		gravityConfig.setChannelIdleTimeoutMillis(channelIdleTimeoutMillis);
	}

	public boolean isRetryOnError() {
		return gravityConfig.isRetryOnError();
	}
	public void setRetryOnError(boolean retryOnError) {
		gravityConfig.setRetryOnError(retryOnError);
	}

	public long getLongPollingTimeoutMillis() {
		return gravityConfig.getLongPollingTimeoutMillis();
	}
	public void setLongPollingTimeoutMillis(long longPollingTimeoutMillis) {
		gravityConfig.setLongPollingTimeoutMillis(longPollingTimeoutMillis);
	}

	public int getMaxMessagesQueuedPerChannel() {
		return gravityConfig.getMaxMessagesQueuedPerChannel();
	}
	public void setMaxMessagesQueuedPerChannel(int maxMessagesQueuedPerChannel) {
		gravityConfig.setMaxMessagesQueuedPerChannel(maxMessagesQueuedPerChannel);
	}

	public long getReconnectIntervalMillis() {
		return gravityConfig.getReconnectIntervalMillis();
	}

	public int getReconnectMaxAttempts() {
		return gravityConfig.getReconnectMaxAttempts();
	}

    public int getCorePoolSize() {
    	if (gravityPool != null)
    		return gravityPool.getCorePoolSize();
    	return gravityConfig.getCorePoolSize();
	}

	public void setCorePoolSize(int corePoolSize) {
		gravityConfig.setCorePoolSize(corePoolSize);
		if (gravityPool != null)
    		gravityPool.setCorePoolSize(corePoolSize);
	}

	public long getKeepAliveTimeMillis() {
    	if (gravityPool != null)
    		return gravityPool.getKeepAliveTimeMillis();
    	return gravityConfig.getKeepAliveTimeMillis();
	}
	public void setKeepAliveTimeMillis(long keepAliveTimeMillis) {
		gravityConfig.setKeepAliveTimeMillis(keepAliveTimeMillis);
		if (gravityPool != null)
    		gravityPool.setKeepAliveTimeMillis(keepAliveTimeMillis);
	}

	public int getMaximumPoolSize() {
    	if (gravityPool != null)
    		return gravityPool.getMaximumPoolSize();
    	return gravityConfig.getMaximumPoolSize();
	}
	public void setMaximumPoolSize(int maximumPoolSize) {
		gravityConfig.setMaximumPoolSize(maximumPoolSize);
		if (gravityPool != null)
    		gravityPool.setMaximumPoolSize(maximumPoolSize);
	}

	public int getQueueCapacity() {
    	if (gravityPool != null)
    		return gravityPool.getQueueCapacity();
    	return gravityConfig.getQueueCapacity();
	}

	public int getQueueRemainingCapacity() {
    	if (gravityPool != null)
    		return gravityPool.getQueueRemainingCapacity();
    	return gravityConfig.getQueueCapacity();
	}

	public int getQueueSize() {
    	if (gravityPool != null)
    		return gravityPool.getQueueSize();
    	return 0;
	}

    ///////////////////////////////////////////////////////////////////////////
    // Channel's operations.
    
    protected <C extends Channel> C createChannel(ChannelFactory<C> channelFactory, String clientId) {
    	C channel = null;
    	if (clientId != null) {
    		channel = getChannel(channelFactory, clientId);
	    	if (channel != null)
	    		return channel;
    	}
    	
    	String clientType = GraniteContext.getCurrentInstance().getClientType();
    	channel = channelFactory.newChannel(UUIDUtil.randomUUID(), clientType);
    	TimeChannel<C> timeChannel = new TimeChannel<C>(channel);
        for (int i = 0; channels.putIfAbsent(channel.getId(), timeChannel) != null; i++) {
            if (i >= 10)
                throw new RuntimeException("Could not find random new clientId after 10 iterations");
            channel.destroy();
            channel = channelFactory.newChannel(UUIDUtil.randomUUID(), clientType);
            timeChannel = new TimeChannel<C>(channel);
        }

        String channelId = channel.getId();
        
        // Save channel id in distributed data (clustering).
        try {
	        DistributedData gdd = graniteConfig.getDistributedDataFactory().getInstance();
	        if (gdd != null) {
        		log.debug("Saving channel id in distributed data: %s", channelId);
	        	gdd.addChannelId(channelId, channelFactory.getClass().getName());
	        }
        }
        catch (Exception e) {
			log.error(e, "Could not add channel id in distributed data: %s", channelId);
        }
        
        // Initialize timer task.
        access(channelId);
    	
        return channel;
    }

    @SuppressWarnings("unchecked")
	public <C extends Channel> C getChannel(ChannelFactory<C> channelFactory, String clientId) {
        if (clientId == null)
            return null;

		TimeChannel<C> timeChannel = (TimeChannel<C>)channels.get(clientId);
        if (timeChannel == null) {
	        // Look for existing channel id/subscriptions in distributed data (clustering).
        	try {
	        	DistributedData gdd = graniteConfig.getDistributedDataFactory().getInstance();
	        	if (gdd != null && gdd.hasChannelId(clientId)) {
	        		log.debug("Found channel id in distributed data: %s", clientId);
	        		String channelFactoryClassName = gdd.getChannelFactoryClassName(clientId);
	        		channelFactory = TypeUtil.newInstance(channelFactoryClassName, ChannelFactory.class);
	        		String clientType = GraniteContext.getCurrentInstance().getClientType();
	        		C channel = channelFactory.newChannel(clientId, clientType);
	    	    	timeChannel = new TimeChannel<C>(channel);
	    	        if (channels.putIfAbsent(clientId, timeChannel) == null) {
	    	        	for (CommandMessage subscription : gdd.getSubscriptions(clientId)) {
	    	        		log.debug("Resubscribing channel: %s - %s", clientId, subscription);
	    	        		handleSubscribeMessage(channelFactory, subscription, false);
	    	        	}
	    	        	access(clientId);
	    	        }
	        	}
        	}
        	catch (Exception e) {
    			log.error(e, "Could not recreate channel/subscriptions from distributed data: %s", clientId);
        	}
        }

        return (timeChannel != null ? timeChannel.getChannel() : null);
    }

    public Channel removeChannel(String channelId) {
        if (channelId == null)
            return null;

        // Remove existing channel id/subscriptions in distributed data (clustering).
        try {
	        DistributedData gdd = graniteConfig.getDistributedDataFactory().getInstance();
	        if (gdd != null) {
        		log.debug("Removing channel id from distributed data: %s", channelId);
	        	gdd.removeChannelId(channelId);
	        }
		}
		catch (Exception e) {
			log.error(e, "Could not remove channel id from distributed data: %s", channelId);
		}
        
        TimeChannel<?> timeChannel = channels.get(channelId);
        Channel channel = null;
        if (timeChannel != null) {
        	try {
        		if (timeChannel.getTimerTask() != null)
        			timeChannel.getTimerTask().cancel();
        	}
        	catch (Exception e) {
        		// Should never happen...
        	}
        	
        	channel = timeChannel.getChannel();
        	
        	try {
	            for (Subscription subscription : channel.getSubscriptions()) {
	            	try {
		            	Message message = subscription.getUnsubscribeMessage();
		            	handleMessage(channel.getFactory(), message, true);
	            	}
	            	catch (Exception e) {
	            		log.error(e, "Error while unsubscribing channel: %s from subscription: %s", channel, subscription);
	            	}
	            }
        	}
        	finally {
        		try {
        			channel.destroy();
        		}
        		finally {
        			channels.remove(channelId);
        		}
        	}
        }

        return channel;
    }
    
    public boolean access(String channelId) {
    	if (channelId != null) {
	    	TimeChannel<?> timeChannel = channels.get(channelId);
	    	if (timeChannel != null) {
	    		synchronized (timeChannel) {
	    			TimerTask timerTask = timeChannel.getTimerTask();
		    		if (timerTask != null) {
			            log.debug("Canceling TimerTask: %s", timerTask);
			            timerTask.cancel();
		    			timeChannel.setTimerTask(null);
		    		}
		            
		    		timerTask = new ChannelTimerTask(this, channelId);
		            timeChannel.setTimerTask(timerTask);
		            
		            long timeout = gravityConfig.getChannelIdleTimeoutMillis();
		            log.debug("Scheduling TimerTask: %s for %s ms.", timerTask, timeout);
		            channelsTimer.schedule(timerTask, timeout);
		            return true;
	    		}
	    	}
    	}
    	return false;
    }
    
    public void execute(AsyncChannelRunner runner) {
    	if (gravityPool == null) {
    		runner.reset();
    		throw new NullPointerException("Gravity not started or pool disabled");
    	}
    	gravityPool.execute(runner);
    }
    
    public boolean cancel(AsyncChannelRunner runner) {
    	if (gravityPool == null) {
    		runner.reset();
    		throw new NullPointerException("Gravity not started or pool disabled");
    	}
    	return gravityPool.remove(runner);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Incoming message handling.

    public Message handleMessage(final ChannelFactory<?> channelFactory, Message message) {
    	return handleMessage(channelFactory, message, false);
    }

    public Message handleMessage(final ChannelFactory<?> channelFactory, final Message message, boolean skipInterceptor) {

        AMF3MessageInterceptor interceptor = null;
        if (!skipInterceptor)
        	interceptor = GraniteContext.getCurrentInstance().getGraniteConfig().getAmf3MessageInterceptor();
        
        Message reply = null;
        
        try {
	        if (interceptor != null)
	            interceptor.before(message);

	        if (message instanceof CommandMessage) {
	            CommandMessage command = (CommandMessage)message;
	
	            switch (command.getOperation()) {
	
	            case CommandMessage.LOGIN_OPERATION:
	            case CommandMessage.LOGOUT_OPERATION:
	                return handleSecurityMessage(command);
	
	            case CommandMessage.CLIENT_PING_OPERATION:
	                return handlePingMessage(channelFactory, command);
	            case CommandMessage.CONNECT_OPERATION:
	                return handleConnectMessage(channelFactory, command);
	            case CommandMessage.DISCONNECT_OPERATION:
	                return handleDisconnectMessage(channelFactory, command);
	            case CommandMessage.SUBSCRIBE_OPERATION:
	                return handleSubscribeMessage(channelFactory, command);
	            case CommandMessage.UNSUBSCRIBE_OPERATION:
	                return handleUnsubscribeMessage(channelFactory, command);
	
	            default:
	                throw new UnsupportedOperationException("Unsupported command operation: " + command);
	            }
	        }
	
	        reply = handlePublishMessage(channelFactory, (AsyncMessage)message);
        }
        finally {
	        if (interceptor != null)
	            interceptor.after(message, reply);
        }
        
        if (reply != null) {
	        GraniteContext context = GraniteContext.getCurrentInstance();
	        if (context.getSessionId() != null)
	        	reply.setHeader("org.granite.sessionId", context.getSessionId());
        }
        
        return reply;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Other Public API methods.

    public GraniteContext initThread(String sessionId, String clientType) {
        GraniteContext context = GraniteContext.getCurrentInstance();
        if (context == null)
            context = SimpleGraniteContext.createThreadInstance(graniteConfig, servicesConfig, sessionId, applicationMap, clientType);
        return context;
    }
    
    public void releaseThread() {
    	GraniteContext.release();
	}

	public Message publishMessage(AsyncMessage message) {
    	return publishMessage(serverChannel, message);
    }

    public Message publishMessage(Channel fromChannel, AsyncMessage message) {
        initThread(null, fromChannel != null ? fromChannel.getClientType() : serverChannel.getClientType());

        return handlePublishMessage(null, message, fromChannel != null ? fromChannel : serverChannel);
    }

    private Message handlePingMessage(ChannelFactory<?> channelFactory, CommandMessage message) {
        
    	Channel channel = createChannel(channelFactory, (String)message.getClientId());
    	
        AsyncMessage reply = new AcknowledgeMessage(message);
        reply.setClientId(channel.getId());
        Map<String, Object> advice = new HashMap<String, Object>();
        advice.put(RECONNECT_INTERVAL_MS_KEY, Long.valueOf(gravityConfig.getReconnectIntervalMillis()));
        advice.put(RECONNECT_MAX_ATTEMPTS_KEY, Long.valueOf(gravityConfig.getReconnectMaxAttempts()));
        reply.setBody(advice);
        reply.setDestination(message.getDestination());

        log.debug("handshake.handle: reply=%s", reply);

        return reply;
    }

    private Message handleSecurityMessage(CommandMessage message) {
        GraniteConfig config = GraniteContext.getCurrentInstance().getGraniteConfig();

        Message response = null;

        if (!config.hasSecurityService())
            log.warn("Ignored security operation (no security settings in granite-config.xml): %s", message);
        else if (!config.getSecurityService().acceptsContext())
            log.info("Ignored security operation (security service does not handle this kind of granite context)", message);
        else {
            SecurityService securityService = config.getSecurityService();
            try {
                if (message.isLoginOperation())
                    securityService.login(message.getBody(), (String)message.getHeader(Message.CREDENTIALS_CHARSET_HEADER));
                else
                    securityService.logout();
            }
            catch (Exception e) {
                if (e instanceof SecurityServiceException)
                    log.debug(e, "Could not process security operation: %s", message);
                else
                    log.error(e, "Could not process security operation: %s", message);
                response = new ErrorMessage(message, e, true);
            }
        }

        if (response == null) {
            response = new AcknowledgeMessage(message, true);
            // For SDK 2.0.1_Hotfix2.
            if (message.isSecurityOperation())
                response.setBody("success");
        }

        return response;
    }

    private Message handleConnectMessage(final ChannelFactory<?> channelFactory, CommandMessage message) {
        Channel client = getChannel(channelFactory, (String)message.getClientId());

        if (client == null)
            return handleUnknownClientMessage(message);

        return null;
    }

    private Message handleDisconnectMessage(final ChannelFactory<?> channelFactory, CommandMessage message) {
        Channel client = getChannel(channelFactory, (String)message.getClientId());
        if (client == null)
            return handleUnknownClientMessage(message);

        removeChannel(client.getId());

        AcknowledgeMessage reply = new AcknowledgeMessage(message);
        reply.setDestination(message.getDestination());
        reply.setClientId(client.getId());
        return reply;
    }

    private Message handleSubscribeMessage(final ChannelFactory<?> channelFactory, final CommandMessage message) {
    	return handleSubscribeMessage(channelFactory, message, true);
    }
    
    private Message handleSubscribeMessage(final ChannelFactory<?> channelFactory, final CommandMessage message, final boolean saveMessageInSession) {

        final GraniteContext context = GraniteContext.getCurrentInstance();

        // Get and check destination.
        final Destination destination = context.getServicesConfig().findDestinationById(
            message.getMessageRefType(),
            message.getDestination()
        );

        if (destination == null)
            return getInvalidDestinationError(message);


        GravityInvocationContext invocationContext = new GravityInvocationContext(message, destination) {
			@Override
			public Object invoke() throws Exception {
		        // Subscribe...
		        Channel channel = getChannel(channelFactory, (String)message.getClientId());
		        if (channel == null)
		            return handleUnknownClientMessage(message);

		        String subscriptionId = (String)message.getHeader(AsyncMessage.DESTINATION_CLIENT_ID_HEADER);
		        if (subscriptionId == null) {
		            subscriptionId = UUIDUtil.randomUUID();
		            message.setHeader(AsyncMessage.DESTINATION_CLIENT_ID_HEADER, subscriptionId);
		        }
		        
		        DistributedData gdd = graniteConfig.getDistributedDataFactory().getInstance();
		        if (gdd != null) {
		        	if (Boolean.TRUE.toString().equals(destination.getProperties().get("session-selector"))) {
		        		String selector = gdd.getDestinationSelector(destination.getId());
		        		log.debug("Session selector found: %s", selector);
		        		if (selector != null)
		        			message.setHeader(CommandMessage.SELECTOR_HEADER, selector);
		        	}
		        }

		        ServiceAdapter adapter = adapterFactory.getServiceAdapter(message);
		        
		        AsyncMessage reply = (AsyncMessage)adapter.manage(channel, message);
		        
		        postManage(channel);
		        
		        if (saveMessageInSession && !(reply instanceof ErrorMessage)) {
		        	// Save subscription message in distributed data (clustering).
		        	try {
			        	if (gdd != null) {
			        		log.debug("Saving new subscription message for channel: %s - %s", channel.getId(), message);
			        		gdd.addSubcription(channel.getId(), message);
			        	}
		        	}
		        	catch (Exception e) {
		        		log.error(e, "Could not add subscription in distributed data: %s - %s", channel.getId(), subscriptionId);
		        	}
		        }

		        reply.setDestination(message.getDestination());
		        reply.setClientId(channel.getId());
		        reply.getHeaders().putAll(message.getHeaders());

		        if (gdd != null && message.getDestination() != null) {
		        	gdd.setDestinationClientId(message.getDestination(), channel.getId());
		        	gdd.setDestinationSubscriptionId(message.getDestination(), subscriptionId);
		        }

		        return reply;
			}       	
        };

        // Check security 1 (destination).
        if (destination.getSecurizer() instanceof GravityDestinationSecurizer) {
            try {
                ((GravityDestinationSecurizer)destination.getSecurizer()).canSubscribe(invocationContext);
            } 
            catch (Exception e) {
                return new ErrorMessage(message, e);
            }
        }
        
        // Check security 2 (security service).
        GraniteConfig config = context.getGraniteConfig();
        try {
            if (config.hasSecurityService() && config.getSecurityService().acceptsContext())
            	return (Message)config.getSecurityService().authorize(invocationContext);
        	
            return (Message)invocationContext.invoke();
        }
        catch (Exception e) {
            return new ErrorMessage(message, e, true);
        }
    }

    private Message handleUnsubscribeMessage(final ChannelFactory<?> channelFactory, CommandMessage message) {
    	Channel channel = getChannel(channelFactory, (String)message.getClientId());
        if (channel == null)
            return handleUnknownClientMessage(message);

        AsyncMessage reply = null;

        ServiceAdapter adapter = adapterFactory.getServiceAdapter(message);
        
        reply = (AcknowledgeMessage)adapter.manage(channel, message);
        
        postManage(channel);
        
        if (!(reply instanceof ErrorMessage)) {
        	// Remove subscription message in distributed data (clustering).
        	try {
		        DistributedData gdd = graniteConfig.getDistributedDataFactory().getInstance();
		        if (gdd != null) {
		        	String subscriptionId = (String)message.getHeader(AsyncMessage.DESTINATION_CLIENT_ID_HEADER);
		        	log.debug("Removing subscription message from channel info: %s - %s", channel.getId(), subscriptionId);
	    			gdd.removeSubcription(channel.getId(), subscriptionId);
		        }
        	}
        	catch (Exception e) {
        		log.error(
        			e, "Could not remove subscription from distributed data: %s - %s",
        			channel.getId(), message.getHeader(AsyncMessage.DESTINATION_CLIENT_ID_HEADER)
        		);
        	}
        }

        reply.setDestination(message.getDestination());
        reply.setClientId(channel.getId());
        reply.getHeaders().putAll(message.getHeaders());

        return reply;
    }
    
    protected void postManage(Channel channel) {
    }

    private Message handlePublishMessage(final ChannelFactory<?> channelFactory, final AsyncMessage message) {
    	return handlePublishMessage(channelFactory, message, null);
    }
    
    private Message handlePublishMessage(final ChannelFactory<?> channelFactory, final AsyncMessage message, final Channel channel) {

        GraniteContext context = GraniteContext.getCurrentInstance();

        // Get and check destination.
        Destination destination = context.getServicesConfig().findDestinationById(
            message.getClass().getName(),
            message.getDestination()
        );

        if (destination == null)
            return getInvalidDestinationError(message);
                
        if (message.getMessageId() == null)
        	message.setMessageId(UUIDUtil.randomUUID());
        message.setTimestamp(System.currentTimeMillis());
        if (channel != null)
        	message.setClientId(channel.getId());

        GravityInvocationContext invocationContext = new GravityInvocationContext(message, destination) {
			@Override
			public Object invoke() throws Exception {
		        // Publish...
		        Channel fromChannel = channel;
		        if (fromChannel == null)
		        	fromChannel = getChannel(channelFactory, (String)message.getClientId());
		        if (fromChannel == null)
		            return handleUnknownClientMessage(message);

		        ServiceAdapter adapter = adapterFactory.getServiceAdapter(message);
		        
		        AsyncMessage reply = (AsyncMessage)adapter.invoke(fromChannel, message);

		        reply.setDestination(message.getDestination());
		        reply.setClientId(fromChannel.getId());

		        return reply;
			}       	
        };

        // Check security 1 (destination).
        if (destination.getSecurizer() instanceof GravityDestinationSecurizer) {
            try {
                ((GravityDestinationSecurizer)destination.getSecurizer()).canPublish(invocationContext);
            } 
            catch (Exception e) {
                return new ErrorMessage(message, e, true);
            }
        }
	
        // Check security 2 (security service).
        GraniteConfig config = context.getGraniteConfig();
        try {
        	if (config.hasSecurityService() && config.getSecurityService().acceptsContext())
                return (Message)config.getSecurityService().authorize(invocationContext);

        	return (Message)invocationContext.invoke();
        } 
        catch (Exception e) {
            return new ErrorMessage(message, e, true);
        }
    }

    private Message handleUnknownClientMessage(Message message) {
        ErrorMessage reply = new ErrorMessage(message, true);
        reply.setFaultCode("Server.Call.UnknownClient");
        reply.setFaultString("Unknown client");
        return reply;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Utilities.

    private ErrorMessage getInvalidDestinationError(Message message) {

        String messageType = message.getClass().getName();
        if (message instanceof CommandMessage)
            messageType += '[' + ((CommandMessage)message).getMessageRefType() + ']';

        ErrorMessage reply = new ErrorMessage(message, true);
        reply.setFaultCode("Server.Messaging.InvalidDestination");
        reply.setFaultString(
            "No configured destination for id: " + message.getDestination() +
            " and message type: " + messageType
        );
        return reply;
    }

    private static class ServerChannel extends AbstractChannel implements Serializable {

		private static final long serialVersionUID = 1L;
		
		public ServerChannel(Gravity gravity, String channelId, ChannelFactory<ServerChannel> factory, String clientType) {
    		super(gravity, channelId, factory, clientType);
    	}

		@Override
		public Gravity getGravity() {
			return gravity;
		}
		
		public void close() {
		}		

		@Override
		public void receive(AsyncMessage message) throws MessageReceivingException {
		}

		@Override
		protected boolean hasAsyncHttpContext() {
			return false;
		}

		@Override
		protected AsyncHttpContext acquireAsyncHttpContext() {
			return null;
		}

		@Override
		protected void releaseAsyncHttpContext(AsyncHttpContext context) {
		}
    }
}
