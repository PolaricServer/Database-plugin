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
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.core.*;
import no.polaric.core.httpd.*;
import no.polaric.core.auth.*;

import java.io.*;
import java.util.*;
import java.net.http.*;


 
public class UserTsSync implements Sync.Handler
{
    private ServerConfig _api;   
    private PluginApi _dbp;
   
    private UserDb _users; 
   
   
   
    public UserTsSync(ServerConfig api, PluginApi dbp) 
    {
        _api=api; _dbp=dbp;
        _users = (UserDb) api.getWebserver().userDb();
    }
   
    public boolean isDelWins() {return false;}
   
   
    /**
     * Handle an update from other node. 
     * Arguments: 
     *  upd - ItemUpdate structure: 
     *      cmd    - Command as string (in this case: In this case only 'UPD' (update). 
     *      arg    - JSON encoded argument (application dependent). In this case it is a timestamp.
     *      userid - String. User that initiated the change.
     *      itemid - String. Some identifier for the item to be updated. In this case it is the userid. 
     */
     
    public void handle(Sync.ItemUpdate upd)
        throws DBSession.SessionError
    {
        if ("UPD".equals(upd.cmd) || "ADD".equals(upd.cmd)) {
            ClientUserSyncer.TimeUpdate tu = (ClientUserSyncer.TimeUpdate) ServerBase.fromJson(upd.arg, ClientUserSyncer.TimeUpdate.class);
            if (tu==null)
                _dbp.log().error("UserTsSync", "TimeUpdate deserialisation failed");
            else
                _upd(upd.itemid, tu.ts, upd.userid);
        }
        else
           // error("Unknown command");
           ;
    }
   
     
     
    private void _upd(String ident, Date ts, String userid)
        throws DBSession.SessionError
    {        
        User u = _users.get(ident);
        if (u==null) 
            _dbp.log().warn("UserTsSync", "Update user ts: non-existent: "+ident);
        else if (u.getLastUsed() == null || ts.getTime() > u.getLastUsed().getTime())
            u.setLastUsed(ts);
    }
     

}
