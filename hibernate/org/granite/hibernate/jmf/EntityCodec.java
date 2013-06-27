/*
  GRANITE DATA SERVICES
  Copyright (C) 2013 GRANITE DATA SERVICES S.A.S.

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

package org.granite.hibernate.jmf;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.persistence.Entity;
import javax.persistence.MappedSuperclass;

import org.granite.logging.Logger;
import org.granite.messaging.jmf.ExtendedObjectInput;
import org.granite.messaging.jmf.ExtendedObjectOutput;
import org.granite.messaging.jmf.codec.ExtendedObjectCodec;
import org.granite.messaging.jmf.reflect.Property;
import org.hibernate.proxy.AbstractSerializableProxy;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;

/**
 * @author Franck WOLFF
 */
public class EntityCodec implements ExtendedObjectCodec {

	private static final Logger log = Logger.getLogger(EntityCodec.class);
	
	private final ConcurrentMap<Class<?>, SerializableProxyAdapter> serializableProxyAdapters = new ConcurrentHashMap<Class<?>, SerializableProxyAdapter>();
		
	static class SerializableProxyAdapter {
		
		private final AbstractSerializableProxy serializableProxy;
		private final Field idField;
		private final Method readResolveMethod;
		
		public SerializableProxyAdapter(Object serializableProxy) throws NoSuchFieldException, NoSuchMethodException, SecurityException {
			this.serializableProxy = (AbstractSerializableProxy)serializableProxy;
			
			this.idField = AbstractSerializableProxy.class.getDeclaredField("id");
			this.idField.setAccessible(true);
			
			this.readResolveMethod = serializableProxy.getClass().getDeclaredMethod("readResolve");
			this.readResolveMethod.setAccessible(true);
		}
		
		public synchronized HibernateProxy getProxy(Serializable id) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
			idField.set(serializableProxy, id);
			return (HibernateProxy)readResolveMethod.invoke(serializableProxy);
		}
	}
	

	public boolean canEncode(ExtendedObjectOutput out, Object v) {
		Class<?> cls = getClass(out, v);
		return (cls.isAnnotationPresent(Entity.class) || cls.isAnnotationPresent(MappedSuperclass.class));
	}

	public String getEncodedClassName(ExtendedObjectOutput out, Object v) {
        return getClass(out, v).getName();
	}

	public void encode(ExtendedObjectOutput out, Object v) throws IOException, IllegalAccessException, InvocationTargetException {
        String detachedState = null;
        
        if (v instanceof HibernateProxy) {
            HibernateProxy proxy = (HibernateProxy)v;

            // Only write initialized flag, detachedState & id if v is an uninitialized proxy.
            if (proxy.getHibernateLazyInitializer().isUninitialized()) {
            	
            	Class<?> persistentClass = proxy.getHibernateLazyInitializer().getPersistentClass();
            	if (!serializableProxyAdapters.containsKey(persistentClass)) {
            		try {
	            		SerializableProxyAdapter proxyAdapter = new SerializableProxyAdapter(proxy.writeReplace());
	            		serializableProxyAdapters.putIfAbsent(persistentClass, proxyAdapter);
            		}
            		catch (Exception e) {
            			throw new IOException("Could not create SerializableProxyAdapter for: " + proxy);
            		}
            	}
            	
            	Serializable id = proxy.getHibernateLazyInitializer().getIdentifier();
            	log.debug("Writing uninitialized HibernateProxy %s with id %s", detachedState, id);
            	
            	out.writeBoolean(false);
            	out.writeUTF(null);
                out.writeObject(id);
                return;
            }

            // Proxy is initialized, get the underlying persistent object.
        	log.debug("Writing initialized HibernateProxy...");
            v = proxy.getHibernateLazyInitializer().getImplementation();
        }

        // Write initialized flag & detachedState.
        out.writeBoolean(true);
        out.writeUTF(null);
		
        // Write all fields in lexical order. 
		List<Property> fields = out.getReflection().findSerializableFields(v.getClass());
		for (Property field : fields)
			out.getAndWriteField(v, field);
	}

	public boolean canDecode(ExtendedObjectInput in, String className) throws ClassNotFoundException {
		Class<?> cls = in.getReflection().loadClass(className);
		return (cls.isAnnotationPresent(Entity.class) || cls.isAnnotationPresent(MappedSuperclass.class));
	}

	public String getDecodedClassName(ExtendedObjectInput in, String className) {
		return in.getAlias(className);
	}

	public Object newInstance(ExtendedObjectInput in, String className)
		throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException,
		InvocationTargetException, SecurityException, NoSuchMethodException, IOException {
		
		Class<?> cls = in.getReflection().loadClass(className);
		
        // Read initialized flag & detachedState.
		boolean initialized = in.readBoolean();
    	in.readUTF();
		
		if (initialized)
			return in.getReflection().newInstance(cls);
		
        // Create an HibernateProxy.
		SerializableProxyAdapter proxyAdapter = serializableProxyAdapters.get(cls);
		if (proxyAdapter == null)
			throw new IOException("Could not find SerializableProxyAdapter for: " + cls);
		Serializable id = (Serializable)in.readObject();
		return proxyAdapter.getProxy(id);
	}

	public void decode(ExtendedObjectInput in, Object v) throws IOException, ClassNotFoundException, IllegalAccessException {
		if (!(v instanceof HibernateProxy)) {
			List<Property> fields = in.getReflection().findSerializableFields(v.getClass());
			for (Property field : fields)
				in.readAndSetField(v, field);
		}
	}
	
	protected Class<?> getClass(ExtendedObjectOutput out, Object v) {
        Class<?> cls = v.getClass();
		
        if (v instanceof HibernateProxy) {
	        LazyInitializer initializer = ((HibernateProxy)v).getHibernateLazyInitializer();
	        
	        String className = (
	        	initializer.isUninitialized() ?
	        	initializer.getEntityName() :
	        	initializer.getImplementation().getClass().getName()
	        );

	        if (className != null && className.length() > 0) {
            	try {
					cls = out.getReflection().loadClass(className);
				} catch (ClassNotFoundException e) {
			        cls = initializer.getPersistentClass();
				}
            }
		}
		
        return cls;
	}
}
