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

import no.arctic.core.*;
import no.arctic.core.httpd.*;
import no.arctic.core.auth.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import java.sql.*;
import javax.sql.*;
import java.net.*;

 
public class DbSyncApi extends ServerBase
{
    private ServerConfig _api; 
    private PluginApi _dbp;
    private DbSync    _dbsync;
    
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
       
    public DbSyncApi(ServerConfig api, DbSync d) {
        super (api); 
        _api = api;
        _dbsync = d;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
    }
        


     
    /** 
     * Return an error status message to client 
     */
    public Object ERROR(Context ctx, int status, String msg)
      { ctx.status(status); ctx.result(msg); return null;}
    
      
      
    public Object ABORT(Context ctx, SyncDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("DbSyncApi", logmsg);
        if (db!=null)
            db.abort();
        return ERROR(ctx, status, msg);
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
        
        /* '/sync/*' is for users. '/dbsync/*' is for peer nodes */
        protect("/dbsync/*");
        protect("/sync/*");
        
        
        
   
        /******************************************
         * For clients: Get nodes
         ******************************************/
         
        a.get("/sync/nodes", (ctx) -> {  
            /* Database transaction */
            if (!getAuthInfo(ctx).admin) {
                ERROR(ctx, 403, "Access denied (need admin)");
                return;
            }
            
            SyncDBSession db = new SyncDBSession(_dbp.getDB(false));
            try { 
                DbList<SyncDBSession.SyncPeer> tr =  db.getSyncPeers(false);
                List<SyncDBSession.SyncPeer> trl = tr.toList();
                
                for (SyncDBSession.SyncPeer x : trl) 
                    if ( _dbsync.isConnected(x.nodeid))
                        x.setActive();
                ctx.json(trl);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /sync/nodes: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        });
        
        
        
        /******************************************
         * For clients: Add a parent node
         ******************************************/
         
        a.post("/sync/nodes", (ctx) -> {   
        
            if (!getAuthInfo(ctx).admin) 
                ERROR(ctx, 403, "Access denied (need admin)");
            else try {        
                NodeInfo ni = (NodeInfo) 
                    ServerBase.fromJson(ctx.body(), NodeInfo.class);
                if (ni==null) {
                    ERROR(ctx, 400, "Cannot parse input");   
                    return;
                }
                if (ni.url == null) {
                    ERROR(ctx, 400, "Parent node must have a URL");
                    return;
                }
                String nodeid = _dbsync.getNodeId(ni.url);
                if (nodeid == null)
                    ERROR(ctx, 500, "Couldn't get nodeid from parent server");
                else if (!_dbsync.addNodeRemote(ni.url, ni.items)) 
                    ERROR(ctx, 500, "Parent subscription failed");
                else if (!_dbsync.addNode(nodeid, ni.items, ni.url))
                    ERROR(ctx, 500, "Couldn't store parent node in database");
                else ctx.result(nodeid);
            } 
            catch (URISyntaxException e) {
                _dbp.log().error("DbSyncApi", ""+e);
                ERROR(ctx, 400, "URI Syntax error: "+e.getMessage());
            }
            catch (IllegalArgumentException e) {
                _dbp.log().error("DbSyncApi", ""+e);
                ERROR(ctx, 400, "Bad input: "+e.getMessage());
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                ERROR(ctx, 500, "Error: "+e.getMessage());
            }
        });
         
        
        
        
        /******************************************
         * Remove a node
         ******************************************/
         
        a.delete("/sync/nodes/{id}", (ctx) -> {   
            if (getAuthInfo(ctx) == null || !getAuthInfo(ctx).admin)
                ERROR(ctx, 403, "Access denied (need admin)");
            else try {        
                var ident = ctx.pathParam("id");
                if (!_dbsync.rmNodeRemote(ident)) {
                    ERROR(ctx, 500, "Parent unsubscribe failed");
                    return;
                }
                _dbsync.rmNode(ident);
                ctx.result("Ok");
            }
            catch (URISyntaxException e) {
                _dbp.log().error("DbSyncApi", ""+e);
                ERROR(ctx, 400, "URI Syntax error: "+e.getMessage());
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                ERROR(ctx, 500, "Error: "+e.getMessage());
            }
        });
        
        
        
        
        /******************************************
         * For peer nodes
         ******************************************
         * Get nodeid for a node
         ******************************************/
        
        a.get("/dbsync/nodeinfo", (ctx) -> {  
            ctx.result(_dbsync.getIdent());
        } );
        
        
        
        
        /******************************************
         * Add a node
         ******************************************/
         
        a.post("/dbsync/nodes", (ctx) -> {   
            try {        
                NodeInfo ni = (NodeInfo) 
                    ServerBase.fromJson(ctx.body(), NodeInfo.class);
                if (ni==null) { 
                    ERROR(ctx, 400, "Cannot parse input");   
                    return;
                }
                if ( ! _dbsync.addNode(ni.nodeid, ni.items, null)) {
                    ERROR(ctx, 500, "Couldn't add node");
                    return;
                }
                ctx.result("Ok");
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                ERROR(ctx, 500, "Couldn't add node: "+e.getMessage());
            }
        });


   
         /******************************************
         * Remove a node
         ******************************************/
         
        a.delete("/dbsync/nodes/{id}", (ctx) -> {   
            try {        
                var ident = ctx.pathParam("id");
                _dbsync.rmNode(ident);
                ctx.result("Ok");
            }
            catch (Exception e) {
                _dbp.log().error("DbSyncApi", ""+e);
                e.printStackTrace(System.out);
                ERROR(ctx, 500, "Couldn't delete node: "+e.getMessage());
            }
        });
        
    }
    
}
