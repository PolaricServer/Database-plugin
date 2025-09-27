/* 
 * Copyright (C) 2025 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
 
package no.polaric.aprsdb;
import no.arctic.core.*;
import no.polaric.aprsd.*;
 
 
public interface PluginApi {
    public Logfile log();
    public Sync getSync(); 
    public MyDBSession getDB() throws DBSession.SessionError;
    public MyDBSession getDB(boolean autocommit) throws DBSession.SessionError;
//    public void saveManagedItem(TrackerPoint tp);
}
