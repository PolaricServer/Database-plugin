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
 
 
package no.polaric.aprsdb.dbsync;
import no.arctic.core.*;
import no.arctic.core.auth.*;
import no.arctic.core.httpd.*;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import java.io.*;
import java.util.*;
import java.net.http.*;



/*
 * This class works as a connector between the user-database (which is not database-backed but file-based)
 * and the dbSync replication layer. It can also be used to link from operations on users (delete) to 
 * database-backed data that refers to users: 
 *   -  Signs and photos
 *   -  Trackers and TrTags
 *   -  JsObject (through ObjecAccess table)
 *
 * The following datasets are supported by DbSync subsystem. All except "user" itself references user (owner). 
 *   -  "user"  
 *   -  "signs"  
 *   -  "userts"  
 *   -  "object"  
 *   -  "objshare"  -> "object", ["user"]
 */
 
 
public class ClientUserSyncer implements UserDb.Syncer
{
    public static class TimeUpdate {
        public Date ts; 
        public TimeUpdate() {}
        public TimeUpdate(Date t) {ts=t;}
    }
    
    
    private Sync _sync;         
    private PluginApi _dbp;
    
    
    public ClientUserSyncer(ServerConfig api, Sync sync) 
    {
        _sync = sync;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
    }
    
        
    public void updateTs(String id, Date ts)
    {
        TimeUpdate tu = new TimeUpdate(ts); 
        _sync.localUpdate("userts", id, "_system_", "UPD", ServerBase.toJson(tu));
    }
    

    public void add(String id, Object obj)
    {
        UserApi.UserInfo u = (UserApi.UserInfo) obj; 
        _sync.localUpdate("user", id, "_system_", "ADD", ServerBase.toJson(u));
    }
    
    
    public void update(String id, Object obj)
    {
        UserApi.UserUpdate u = (UserApi.UserUpdate) obj; 
        _sync.localUpdate("user", id, "_system_", "UPD", ServerBase.toJson(u));
    }
    
    
    public void remove(String id)
    {
       /* delete objects referencing (owned by) the user */
 //      _sync.getHandler("user").onDelete(id);     
    
       /* Log and propagate the deletion of user */
       _sync.localUpdate("user", id, "_system_", "DEL", null);
    }
          

    
  
}
