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

package org.granite.messaging.jmf.codec.std.impl;

import org.granite.messaging.jmf.JMFEncodingException;
import org.granite.messaging.jmf.codec.StandardCodec;

/**
 * @author Franck WOLFF
 */
public abstract class AbstractStandardCodec<T> implements StandardCodec<T> {
	
	protected JMFEncodingException newBadTypeJMFEncodingException(int jmfType, int parameterizedJmfType) {
		return new JMFEncodingException(
			"Bad JMF type for " + getClass().getName() + ": " + jmfType +
			" (parameterized: " + parameterizedJmfType + ")"
		);
	}
	
	protected String escape(String s) {
		if (s == null || s.length() == 0)
			return s;
		
		StringBuilder sb = new StringBuilder(s.length());
		
		final int max = s.length();
		for (int i = 0; i < max; i++) {
			char c = s.charAt(i);
			escape(c, sb);
		}
		
		return sb.toString();
	}
	
	protected String escape(char c) {
		StringBuilder sb = new StringBuilder(6);
		escape(c, sb);
		return sb.toString();
	}
	
	protected void escape(char c, StringBuilder sb) {
		if (c >= 0x20 && c <= 0x7F)
			sb.append(c);
		else {
			switch (c) {
				case '\n': sb.append("\\n"); break;
				case '\t': sb.append("\\t"); break;
				case '\r': sb.append("\\r"); break;
				case '\'': sb.append("\\\'"); break;
				case '\"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\b': sb.append("\\b"); break;
				case '\f': sb.append("\\f"); break;
				default: {
					String hex = Integer.toHexString(c);
					switch (hex.length()) {
						case 1: sb.append("\\u000"); break;
						case 2: sb.append("\\u00"); break;
						case 3: sb.append("\\u0"); break;
						default: sb.append("\\u"); break;
					}
					sb.append(hex);
					break;
				}
			}
		}
	}
}
