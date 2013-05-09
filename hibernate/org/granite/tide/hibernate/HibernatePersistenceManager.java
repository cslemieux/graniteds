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

package org.granite.tide.hibernate;

import java.io.Serializable;
import java.util.List;

import org.granite.logging.Logger;
import org.granite.tide.TideTransactionManager;
import org.granite.tide.data.AbstractTidePersistenceManager;
import org.granite.util.Entity;
import org.hibernate.Query;
import org.hibernate.SessionFactory;

/**
 * Responsible for attaching a session with the persistence mangager
 * @author cingram
 *
 */
public class HibernatePersistenceManager extends AbstractTidePersistenceManager {
	
	private static final Logger log = Logger.getLogger(HibernatePersistenceManager.class);
	
	private SessionFactory sessionFactory;
	
	
	public HibernatePersistenceManager(TideTransactionManager tm) {
		super(tm);
	}

	public HibernatePersistenceManager(SessionFactory sf) {
		super(null);
        this.sessionFactory = sf;
	}

	public HibernatePersistenceManager(SessionFactory sf, TideTransactionManager tm) {
		super(tm);
        this.sessionFactory = sf;
	}
	
	@Override
	public void close() {		
	}
	
	/**
	 * attaches the entity to the JPA context.
	 * @return the attached entity
	 */
	@Override
	public Object fetchEntity(Object entity, String[] fetch) {
		Entity tideEntity = new Entity(entity);
		Serializable id = (Serializable)tideEntity.getIdentifier();
		
        if (id == null)
            return null;

        if (fetch == null)
        	return sessionFactory.getCurrentSession().load(entity.getClass(), id);
        
        for (String f : fetch) {
	        Query q = sessionFactory.getCurrentSession().createQuery("select e from " + entity.getClass().getName() + " e left join fetch e." + f + " where e = :entity");
	        q.setParameter("entity", entity);
	        List<?> results = q.list();
	        if (!results.isEmpty())
	        	entity = results.get(0);
	        else
	        	log.warn("Could not find entity %s to initialize, id: %s", entity.getClass().getName(), id);  
        }
        return entity;
	}
}
