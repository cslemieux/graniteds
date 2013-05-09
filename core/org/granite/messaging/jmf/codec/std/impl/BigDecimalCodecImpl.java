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
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;

import org.granite.messaging.jmf.DumpContext;
import org.granite.messaging.jmf.InputContext;
import org.granite.messaging.jmf.OutputContext;
import org.granite.messaging.jmf.codec.std.BigDecimalCodec;

/**
 * @author Franck WOLFF
 */
public class BigDecimalCodecImpl extends AbstractIntegerStringCodec<BigDecimal> implements BigDecimalCodec {

	public int getObjectType() {
		return JMF_BIG_DECIMAL;
	}

	public Class<?> getObjectClass() {
		return BigDecimal.class;
	}

	public void encode(OutputContext ctx, BigDecimal v) throws IOException {
		final OutputStream os = ctx.getOutputStream();
		
		int indexOfStoredObject = ctx.indexOfStoredObjects(v);
		if (indexOfStoredObject >= 0) {
			IntegerComponents ics = intComponents(indexOfStoredObject);
			os.write(0x80 | (ics.length << 5) | JMF_BIG_DECIMAL);
			writeIntData(ctx, ics);
		}
		else {
			ctx.addToStoredObjects(v);
			
			int scale = v.scale();
			byte[] magnitude = v.unscaledValue().toByteArray();

			IntegerComponents ics = intComponents(magnitude.length);
			os.write((ics.length << 5) | JMF_BIG_DECIMAL);
			writeIntData(ctx, ics);
			
			os.write(scale);
			os.write(magnitude);
		}
	}

	public BigDecimal decode(InputContext ctx, int parameterizedJmfType) throws IOException {
		int jmfType = ctx.getSharedContext().getCodecRegistry().extractJmfType(parameterizedJmfType);
		
		if (jmfType != JMF_BIG_DECIMAL)
			throw newBadTypeJMFEncodingException(jmfType, parameterizedJmfType);
		
		int indexOrLength = readIntData(ctx, (parameterizedJmfType >> 5) & 0x03, false);
		if ((parameterizedJmfType & 0x80) != 0)
			return (BigDecimal)ctx.getSharedObject(indexOrLength);

		int scale = ctx.safeRead();
		
		byte[] magnitude = new byte[indexOrLength];
		ctx.safeReadFully(magnitude);
		
		BigDecimal v = new BigDecimal(new BigInteger(magnitude), scale);
		
		if (BigDecimal.ZERO.equals(v))
			v = BigDecimal.ZERO;
		else if (BigDecimal.ONE.equals(v))
			v = BigDecimal.ONE;
		else if (BigDecimal.TEN.equals(v))
			v = BigDecimal.TEN;
		
		return v;
	}

	public void dump(DumpContext ctx, int parameterizedJmfType) throws IOException {
		int jmfType = ctx.getSharedContext().getCodecRegistry().extractJmfType(parameterizedJmfType);
		if (jmfType != JMF_BIG_DECIMAL)
			throw newBadTypeJMFEncodingException(jmfType, parameterizedJmfType);
		ctx.indentPrintLn(BigDecimal.class.getName() + ": " + decode(ctx, parameterizedJmfType));
	}
}
