
package no.polaric.aprsdb.dbsync;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import uk.me.jstott.jcoord.*; 
import no.polaric.aprsd.filter.*;
import spark.Request;
import spark.Response;
import spark.route.Routes;
import static spark.Spark.get;
import static spark.Spark.put;
import static spark.Spark.*;
import spark.QueryParamsMap;
import java.util.stream.Collectors;
import javax.servlet.*;
import javax.servlet.http.*;
import org.eclipse.jetty.server.*;
import  java.sql.*;
import  javax.sql.*;

 
public class DbSyncApi extends ServerBase
{
    private ServerAPI _api; 
    private PluginApi _dbp;
    private Sync    _dbsync;
    
    
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
       
    public DbSyncApi(ServerAPI api, Sync d) {
        super (api); 
        _api = api;
        _dbsync = d;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
    }
        
    
    
    /** 
     * Return an error status message to client 
     */
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
      
    public String ABORT(Response resp, MyDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("DbSyncApi", logmsg);
        db.abort();
        return ERROR(resp, status, msg);
    }
    
    
    
    private boolean authenticate(Sync.ItemUpdate upd) {
        boolean result = upd.mac.equals( upd.generateMac(_api) );
        return result; 
    }
    
    
    
    
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
    
        _api.getWebserver().corsEnable("/dbsync");
    
            
        /******************************************
         * POST an update
         ******************************************/
        post("/dbsync", (req, resp) -> {   
            try {
                Sync.ItemUpdate upd = (Sync.ItemUpdate) 
                    ServerBase.fromJson(req.body(), Sync.ItemUpdate.class);
                    
                if (upd == null)
                    return ERROR(resp, 400, "Invalid message body");
                
                if (!authenticate(upd))
                    return ERROR(resp, 400, "Message authentication failed");
            
                _dbsync.doUpdate(upd);
                return "Ok";
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                return "ERROR";
            }
            
        });
    }
    
}
