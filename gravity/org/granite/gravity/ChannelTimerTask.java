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

import java.util.TimerTask;

import org.granite.logging.Logger;

/**
 * @author Franck WOLFF
 */
public class ChannelTimerTask extends TimerTask {

    private static final Logger log = Logger.getLogger(ChannelTimerTask.class);

	private final Gravity gravity;
	private final String channelId;
	
	public ChannelTimerTask(Gravity gravity, String channelId) {
		this.gravity = gravity;
		this.channelId = channelId;
	}

	@Override
	public void run() {
		log.debug("Removing channel: %s...", channelId);
		try {
			gravity.initThread(null, null);
			gravity.removeChannel(channelId);
		}
		finally {
			gravity.releaseThread();
		}
		log.debug("Channel: %s removed.", channelId);
	}

	@Override
	public String toString() {
		return getClass().getName() + " {channelId=" + channelId + "}";
	}
}
