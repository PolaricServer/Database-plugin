
package no.polaric.aprsdb.dbsync;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;
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
    
    
    public ClientUserSyncer(ServerAPI api, Sync sync) 
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
