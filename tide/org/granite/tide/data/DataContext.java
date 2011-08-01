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

package org.granite.tide.data;

import java.util.HashSet;
import java.util.Set;

import org.granite.gravity.Gravity;
import org.granite.logging.Logger;
import org.granite.tide.data.DataEnabled.PublishMode;


/**
 * @author William DRAI
 */
public class DataContext {
    
	private static final Logger log = Logger.getLogger(DataContext.class);
	
    private static ThreadLocal<DataContext> dataContext = new ThreadLocal<DataContext>();
    
    private static DataContext NULL_DATA_CONTEXT = new NullDataContext(); 
    
    private DataDispatcher dataDispatcher = null;
    private PublishMode publishMode = null;
    
    
    public static void init() {
    	dataContext.set(NULL_DATA_CONTEXT);
    }
    
    public static void init(String topic, Class<? extends DataTopicParams> dataTopicParamsClass, PublishMode publishMode) {
    	DataContext dc = new DataContext(null, topic, dataTopicParamsClass, publishMode);
    	dataContext.set(dc);
    }
    
    public static void init(Gravity gravity, String topic, Class<? extends DataTopicParams> dataTopicParamsClass, PublishMode publishMode) {
    	DataContext dc = new DataContext(gravity, topic, dataTopicParamsClass, publishMode);
    	dataContext.set(dc);
    }
    
    public static void init(DataDispatcher dataDispatcher, PublishMode publishMode) {
		DataContext dc = new DataContext(dataDispatcher, publishMode);
		dataContext.set(dc);
    }
    
    private DataContext(Gravity gravity, String topic, Class<? extends DataTopicParams> dataTopicParamsClass, PublishMode publishMode) {
		log.debug("Init Gravity data context for topic %s and mode %s", topic, publishMode);
		dataDispatcher = new DefaultDataDispatcher(gravity, topic, dataTopicParamsClass);
		this.publishMode = publishMode;
    }

    private DataContext(DataDispatcher dataDispatcher, PublishMode publishMode) {
		log.debug("Init data context with custom dispatcher %s and mode %s", dataDispatcher, publishMode);
		this.dataDispatcher = dataDispatcher;
		this.publishMode = publishMode;
    }

    public static DataContext get() {
        return dataContext.get();
    }
    
    public static void remove() {
		log.debug("Remove Gravity data context");
    	dataContext.remove();
    }
    
    public static boolean isNull() {
    	return dataContext.get() == NULL_DATA_CONTEXT;
    }
    
    private final Set<Object[]> dataUpdates = new HashSet<Object[]>();
    private boolean published = false;

    
    public Set<Object[]> getDataUpdates() {
        return dataUpdates;
    }
    
    public static void addUpdate(EntityUpdateType type, Object entity) {
    	DataContext dc = get();
    	if (dc != null) {
    		for (Object[] update : dc.dataUpdates) {
    			if (update[0].equals(type) && update[1].equals(entity))
    				return;
    		}
    		dc.dataUpdates.add(new Object[] { type.name(), entity });
    	}
    }
    
    
    public static void observe() {
    	DataContext dc = get();
    	if (dc != null) {
    		log.debug("Observe data updates");
    		dc.dataDispatcher.observe();
    	}
    }
    
    public static void publish() {
    	publish(PublishMode.MANUAL);
    }
    public static void publish(PublishMode publishMode) {
    	DataContext dc = get();
    	if (dc != null && dc.dataDispatcher != null && !dc.dataUpdates.isEmpty() && !dc.published 
    		&& (publishMode == PublishMode.MANUAL || (dc.publishMode.equals(publishMode)))) {
    		log.debug("Publish %s data updates with mode %s", dc.dataUpdates.size(), dc.publishMode);
    		dc.dataDispatcher.publish(dc.dataUpdates);
    		// Publish can be called only once but we have to keep the updates until the end of a GraniteDS request
    		dc.published = true;	
    	}
    }
    
    
    public enum EntityUpdateType {
    	PERSIST,
    	UPDATE,
    	REMOVE
    }
    
    private static class NullDataContext extends DataContext {
    	
    	public NullDataContext() {
    		super(null, null);
    	}
    }
}
