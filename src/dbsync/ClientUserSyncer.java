
package no.polaric.aprsdb.dbsync;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;
import java.io.*;
import java.util.*;
import java.net.http.*;


 
public class ClientUserSyncer implements UserDb.Syncer
{
    public static class TimeUpdate {
        public Date ts; 
        public TimeUpdate() {}
        public TimeUpdate(Date t) {ts=t;}
    }
    
    
    private Sync _sync;         
    
    
    public ClientUserSyncer(Sync sync) 
    {
        _sync = sync;
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
       _sync.localUpdate("user", id, "_system_", "DEL", null);
    }
          
  
}
