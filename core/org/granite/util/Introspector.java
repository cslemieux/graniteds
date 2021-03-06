/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.granite.util;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;

/**
 * 	Basic bean introspector
 *  Required for Android environment which does not include java.beans.Intropector
 */
public class Introspector {

    private static Map<Class<?>, PropertyDescriptor[]> descriptorCache = Collections.synchronizedMap(new WeakHashMap<Class<?>, PropertyDescriptor[]>(128));

    /**
     * Decapitalizes a given string according to the rule:
     * <ul>
     * <li>If the first or only character is Upper Case, it is made Lower Case
     * <li>UNLESS the second character is also Upper Case, when the String is
     * returned unchanged <eul>
     * 
     * @param name -
     *            the String to decapitalize
     * @return the decapitalized version of the String
     */
    public static String decapitalize(String name) {

        if (name == null)
            return null;
        // The rule for decapitalize is that:
        // If the first letter of the string is Upper Case, make it lower case
        // UNLESS the second letter of the string is also Upper Case, in which case no
        // changes are made.
        if (name.length() == 0 || (name.length() > 1 && Character.isUpperCase(name.charAt(1)))) {
            return name;
        }
        
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /**
     * Flushes all <code>BeanInfo</code> caches.
     *  
     */
    public static void flushCaches() {
        // Flush the cache by throwing away the cache HashMap and creating a
        // new empty one
        descriptorCache.clear();
    }

    /**
     * Flushes the <code>BeanInfo</code> caches of the specified bean class
     * 
     * @param clazz
     *            the specified bean class
     */
    public static void flushFromCaches(Class<?> clazz) {
        if (clazz == null)
            throw new NullPointerException();
        
        descriptorCache.remove(clazz);
    }

    /**
	 * Gets the <code>BeanInfo</code> object which contains the information of
	 * the properties, events and methods of the specified bean class.
	 * 
	 * <p>
	 * The <code>Introspector</code> will cache the <code>BeanInfo</code>
	 * object. Subsequent calls to this method will be answered with the cached
	 * data.
	 * </p>
	 * 
	 * @param beanClass
	 *            the specified bean class.
	 * @return the <code>BeanInfo</code> of the bean class.
	 * @throws IntrospectionException
	 */
    public static PropertyDescriptor[] getPropertyDescriptors(Class<?> beanClass) {
        PropertyDescriptor[] descriptor = descriptorCache.get(beanClass);
        if (descriptor == null) {
        	descriptor = new BeanInfo(beanClass).getPropertyDescriptors();
            descriptorCache.put(beanClass, descriptor);
        }
        return descriptor;
    }
    
    
    private static class BeanInfo {

        private Class<?> beanClass;
        private PropertyDescriptor[] properties = null;

        
        public BeanInfo(Class<?> beanClass) {
            this.beanClass = beanClass;

            if (properties == null)
                properties = introspectProperties();
        }

        public PropertyDescriptor[] getPropertyDescriptors() {
            return properties;
        }

        /**
         * Introspects the supplied class and returns a list of the Properties of
         * the class
         * 
         * @return The list of Properties as an array of PropertyDescriptors
         * @throws IntrospectionException
         */
        private PropertyDescriptor[] introspectProperties() {

        	Method[] methods = beanClass.getMethods();
        	List<Method> methodList = new ArrayList<Method>();
        	
        	for (Method method : methods) {
        		if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers()))
        			continue;
        		methodList.add(method);
        	}

            Map<String, Map<String, Object>> propertyMap = new HashMap<String, Map<String, Object>>(methodList.size());

            // Search for methods that either get or set a Property
            for (Method method : methodList) {
                introspectGet(method, propertyMap);
                introspectSet(method, propertyMap);
            }

            // fix possible getter & setter collisions
            fixGetSet(propertyMap);
            
            // Put the properties found into the PropertyDescriptor array
            List<PropertyDescriptor> propertyList = new ArrayList<PropertyDescriptor>();

            for (Map.Entry<String, Map<String, Object>> entry : propertyMap.entrySet()) {
                String propertyName = entry.getKey();
                Map<String, Object> table = entry.getValue();
                if (table == null)
                    continue;
                
                Method getter = (Method)table.get("getter");
                Method setter = (Method)table.get("setter");

                PropertyDescriptor propertyDesc = new PropertyDescriptor(propertyName, getter, setter);
                propertyList.add(propertyDesc);
            }

            PropertyDescriptor[] properties = new PropertyDescriptor[propertyList.size()];
            propertyList.toArray(properties);
            return properties;
        }

        @SuppressWarnings("unchecked")
        private static void introspectGet(Method method, Map<String, Map<String, Object>> propertyMap) {
            String methodName = method.getName();
            
            if (!(method.getName().startsWith("get") || method.getName().startsWith("is")))
            	return;
            
            if (method.getParameterTypes().length > 0 || method.getReturnType() == void.class)
            	return;
            
            if (method.getName().startsWith("is") && method.getReturnType() != boolean.class)
            	return;

            String propertyName = method.getName().startsWith("get") ? methodName.substring(3) : methodName.substring(2);
            propertyName = decapitalize(propertyName);

            Map<String, Object> table = propertyMap.get(propertyName);
            if (table == null) {
                table = new HashMap<String, Object>();
                propertyMap.put(propertyName, table);
            }

            List<Method> getters = (List<Method>)table.get("getters");
            if (getters == null) {
                getters = new ArrayList<Method>();
                table.put("getters", getters);
            }
            getters.add(method);
        }

        @SuppressWarnings("unchecked")
        private static void introspectSet(Method method, Map<String, Map<String, Object>> propertyMap) {
            String methodName = method.getName();
            
            if (!method.getName().startsWith("set"))
            	return;
            
            if (method.getParameterTypes().length != 1 || method.getReturnType() != void.class)
            	return;

            String propertyName = decapitalize(methodName.substring(3));

            Map<String, Object> table = propertyMap.get(propertyName);
            if (table == null) {
                table = new HashMap<String, Object>();
                propertyMap.put(propertyName, table);
            }

            List<Method> setters = (List<Method>)table.get("setters");
            if (setters == null) {
                setters = new ArrayList<Method>();
                table.put("setters", setters);
            }

            // add new setter
            setters.add(method);
        }

        /**
         * Checks and fixs all cases when several incompatible checkers / getters
         * were specified for single property.
         * 
         * @param propertyTable
         * @throws IntrospectionException
         */
        private void fixGetSet(Map<String, Map<String, Object>> propertyMap) {
            if (propertyMap == null)
                return;

            for (Entry<String, Map<String, Object>> entry : propertyMap.entrySet()) {
                Map<String, Object> table = entry.getValue();
                @SuppressWarnings("unchecked")
				List<Method> getters = (List<Method>)table.get("getters");
                @SuppressWarnings("unchecked")
				List<Method> setters = (List<Method>)table.get("setters");
                if (getters == null)
                    getters = new ArrayList<Method>();
                if (setters == null)
                    setters = new ArrayList<Method>();

                Method definedGetter = getters.isEmpty() ? null : getters.get(0);
                Method definedSetter = null;

                if (definedGetter != null) {
    	            Class<?> propertyType = definedGetter.getReturnType();
    	
    	            for (Method setter : setters) {
    	                if (setter.getParameterTypes().length == 1 && propertyType.equals(setter.getParameterTypes()[0])) {
    	                    definedSetter = setter;
    	                    break;
    	                }
    	            }
    	            if (definedSetter != null && !setters.isEmpty())
    	            	definedSetter = setters.get(0);
                } 
                else if (!setters.isEmpty()) {
                	definedSetter = setters.get(0);
                }

                table.put("getter", definedGetter);
                table.put("setter", definedSetter);
            }
        }
    }
}


