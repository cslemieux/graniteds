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

import java.io.IOException;

import org.granite.messaging.jmf.DumpContext;
import org.granite.messaging.jmf.InputContext;
import org.granite.messaging.jmf.JMFEncodingException;
import org.granite.messaging.jmf.OutputContext;
import org.granite.messaging.jmf.codec.std.NullCodec;

/**
 * @author Franck WOLFF
 */
public class NullCodecImpl extends AbstractStandardCodec<Object> implements NullCodec {

	public int getObjectType() {
		return JMF_NULL;
	}

	public Class<?> getObjectClass() {
		return null;
	}

	public void encode(OutputContext ctx, Object v) throws IOException {
		if (v != null)
			throw new JMFEncodingException("Null value must be null");
		
		ctx.getOutputStream().write(JMF_NULL);
	}
	
	public Object decode(InputContext ctx, int parameterizedJmfType) throws IOException {
		int jmfType = ctx.getSharedContext().getCodecRegistry().extractJmfType(parameterizedJmfType);
		
		if (jmfType != JMF_NULL)
			throw newBadTypeJMFEncodingException(jmfType, parameterizedJmfType);
		
		return null;
	}
	
	public void dump(DumpContext ctx, int parameterizedJmfType) throws IOException {
		int jmfType = ctx.getSharedContext().getCodecRegistry().extractJmfType(parameterizedJmfType);
		
		if (jmfType != JMF_NULL)
			throw newBadTypeJMFEncodingException(jmfType, parameterizedJmfType);

		ctx.indentPrintLn("?: null");
	}
}
