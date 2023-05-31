 

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
import java.sql.*;
import javax.sql.*;
import java.net.*;

 
public class DbSyncApi extends ServerBase
{
    private ServerAPI _api; 
    private PluginApi _dbp;
    private DbSync    _dbsync;
    private HmacAuth _auth;
    
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
       
    public DbSyncApi(ServerAPI api, HmacAuth auth, DbSync d) {
        super (api); 
        _api = api;
        _dbsync = d;
        _auth = auth;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
    }
        

    
    /** 
     * Return an error status message to client 
     */
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
      
    public String ABORT(Response resp, SyncDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("DbSyncApi", logmsg);
        db.abort();
        return ERROR(resp, status, msg);
    }
    
    
    public static class NodeInfo {
        public String nodeid, items, url;
        public NodeInfo() {}
        public NodeInfo(String id, String it, String u) {
            nodeid=id; items=it; url=u; 
        }
    }
    
    
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        
        
        _api.getWebserver().protectUrl("/sync/*");
        _api.getWebserver().corsEnable("/sync/*");
        
        
        
   
        /******************************************
         * For clients: Get nodes
         ******************************************/
         
        get("/sync/nodes", (req, resp) -> {  
            /* Database transaction */
            if (!getAuthInfo(req).admin)
                ERROR(resp, 403, "Access denied (need admin)");
            SyncDBSession db = new SyncDBSession(_dbp.getDB(false));
            try { 
                DbList<SyncDBSession.SyncPeer> tr =  db.getSyncPeers(false);
                List<SyncDBSession.SyncPeer> trl = tr.toList();
                
                for (SyncDBSession.SyncPeer x : trl) 
                    if ( _dbsync.isConnected(x.nodeid))
                        x.setActive();
                return trl;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /sync/nodes: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        }, ServerBase::toJson );
        
        
        
        /******************************************
         * For clients: Add a parent node
         ******************************************/
         
        post("/sync/nodes", (req, resp) -> {   
        
            if (!getAuthInfo(req).admin)
                ERROR(resp, 403, "Access denied (need admin)");
            try {        
                NodeInfo ni = (NodeInfo) 
                    ServerBase.fromJson(req.body(), NodeInfo.class);
                if (ni==null) 
                    return ERROR(resp, 400, "Cannot parse input");   
                if (ni.url == null)
                    return ERROR(resp, 400, "Parent node must have a URL");

                String nodeid = _dbsync.getNodeId(ni.url);
                if (nodeid == null)
                    return ERROR(resp, 500, "Couldn't get nodeid from parent server");
                if (!_dbsync.addNodeRemote(ni.url, ni.items)) 
                    return ERROR(resp, 500, "Parent subscription failed");
                if (!_dbsync.addNode(nodeid, ni.items, ni.url))
                    return ERROR(resp, 500, "Couldn't store parent node in database");
                return nodeid;
            } 
            catch (URISyntaxException e) {
                _dbp.log().error("DbSyncApi", ""+e);
                return ERROR(resp, 400, "URI Syntax error: "+e.getMessage());
            }
            catch (IllegalArgumentException e) {
                _dbp.log().error("DbSyncApi", ""+e);
                return ERROR(resp, 400, "Bad input: "+e.getMessage());
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                return ERROR(resp, 500, "Error: "+e.getMessage());
            }
        });
         
        
        
        
        /******************************************
         * Remove a node
         ******************************************/
         
        delete("/sync/nodes/*", (req, resp) -> {   
            if (getAuthInfo(req) == null || !getAuthInfo(req).admin)
                ERROR(resp, 403, "Access denied (need admin)");
            try {        
                String ident = req.splat()[0];
                if (!_dbsync.rmNodeRemote(ident)) 
                    return ERROR(resp, 500, "Parent unsubscribe failed");
                _dbsync.rmNode(ident);
                return "Ok";
            }
            catch (URISyntaxException e) {
                _dbp.log().error("DbSyncApi", ""+e);
                return ERROR(resp, 400, "URI Syntax error: "+e.getMessage());
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                return ERROR(resp, 500, "Error: "+e.getMessage());
            }
        });
        
        
        
        
        /******************************************
         * For peer nodes
         ******************************************
         * Get nodeid for a node
         ******************************************/
        
        get("/dbsync/nodeinfo", (req, resp) -> {  
           if (!_auth.checkAuth(req))
                    return ERROR(resp, 403, "Authentication failed");
            return _dbsync.getIdent();
        } );
        
        
        
        
        /******************************************
         * Add a node
         ******************************************/
         
        post("/dbsync/nodes", (req, resp) -> {   
            try {        
                if (!_auth.checkAuth(req))
                    return ERROR(resp, 403, "Authentication failed");
                NodeInfo ni = (NodeInfo) 
                    ServerBase.fromJson(req.body(), NodeInfo.class);
                if (ni==null) 
                    return ERROR(resp, 400, "Cannot parse input");   
                        
                if ( ! _dbsync.addNode(ni.nodeid, ni.items, null))
                    return ERROR(resp, 500, "Couldn't add node");
                return "Ok";
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                return ERROR(resp, 500, "Couldn't add node: "+e.getMessage());
            }
        });


   
         /******************************************
         * Remove a node
         ******************************************/
         
        delete("/dbsync/nodes/*", (req, resp) -> {   
            try {        
                String ident = req.splat()[0];
                if (!_auth.checkAuth(req))
                    return ERROR(resp, 403, "Authentication failed");
                _dbsync.rmNode(ident);
                return "Ok";
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                return ERROR(resp, 500, "Couldn't delete node: "+e.getMessage());
            }
        });
        
    }
    
}
