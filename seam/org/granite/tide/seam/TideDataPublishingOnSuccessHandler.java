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

package org.granite.tide.seam;

import org.granite.tide.data.DataContext;
import org.granite.tide.data.DataEnabled.PublishMode;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Observer;
import org.jboss.seam.annotations.Scope;


/**
 * Seam event listener to handle publishing of data changes instead of relying on the default behaviour
 * This can be used outside of a HTTP Granite context and inside the security/transaction context
 * @author William DRAI
 *
 */
@Name("org.granite.tide.seam.dataPublisher")
@Scope(ScopeType.EVENT)
public class TideDataPublishingOnSuccessHandler {
	
    @Observer("org.granite.tide.seam.data.transactionSuccess")
    public void doPublish(boolean initContext) {
    	DataContext.publish(PublishMode.ON_COMMIT);
    	if (initContext)
    		DataContext.remove();
    }
	
    @Observer("org.granite.tide.seam.data.transactionCompletion")
    public void doFinish(boolean initContext) {
    	if (initContext)
    		DataContext.remove();
    }
}
