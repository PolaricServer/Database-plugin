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
 
package no.polaric.aprsdb.http;
import no.arctic.core.*;
import no.arctic.core.httpd.*;
import no.arctic.core.auth.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.point.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import java.util.stream.Collectors;



 
public class SignsApi extends ServerBase implements JsonPoints
{
    private ServerConfig _api; 
    private PluginApi _dbp;    
    private PubSub _psub;
    private String    _myCall;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public SignsApi(ServerConfig api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        _myCall = api.getProperty("default.mycall", "NOCALL").toUpperCase();  

    }
        
        
        
    public static class SignInfo {
        public String id; 
        public String url;
        public String descr;
        public String icon;
        public long scale;
        public int type;
        public String tname;
        public double[] pos;
        
        public SignInfo() {}
        public SignInfo(String i, String u, String d, String ic, long sc, int t, String tn, LatLng p)
            {   
                id=i;url=u; descr=d; icon=ic; scale=sc; type=t; tname=tn;
                pos = new double[2]; 
                pos[0] = p.getLng(); 
                pos[1] = p.getLat();
            } 
    }
    

    
    
    /** 
     * Return an error status message to client 
     */
    public Object ERROR(Context ctx, int status, String msg)
      { ctx.status(status); ctx.result(msg); return null;}
    
      
      
    public Object ABORT(Context ctx, SignsDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("SignsApi", logmsg);
        if (db!=null)
            db.abort();
        return ERROR(ctx, status, msg);
    }


    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        protect("/signs", "operator");
        protect("/signs/*", "operator");
                
        _psub = (PubSub) _api.getWebserver().pubSub();
        _psub.createRoom("sign", (Class) null); 
                
        /**************************************************************************** 
         * REST Service
         * get a list of types (categories)
         ****************************************************************************/
         
        a.get("/signs/types", (ctx) -> {
            List<Sign.Category> res = new ArrayList<Sign.Category>(); 
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                DbList<Sign.Category> rr = db.getCategories();
                for (Sign.Category x : rr)
                    res.add(x);
                db.commit(); 
                ctx.json(res);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /signs/types: SQL error:"+e.getMessage(), 500, null);
            }           
            finally { 
                db.close(); 
            }

        });
        
        
                        
        /**************************************************************************** 
         * REST Service
         * get a specific sign
         ****************************************************************************/
         
        a.get ("/signs/{id}", (ctx) -> {
            String ident = ctx.pathParam("id");
            
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                Sign x = db.getSign(ident);
                if (x==null) {
                    ABORT(ctx, db, "GET /signs/*: Object not found: "+ident,
                        404, "Object not found: "+ident);
                    return;
                }
                
                db.commit();
                SignInfo s = new SignInfo(x.getId(), x.getUrl(), x.getDescr(), x.getIcon(), 
                        x.getScale(), x.getCategory(), x.getGroup(), x.getPosition() ); 
                ctx.json(s);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET/signs/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}  
        });
        
        
        
        /**************************************************************************** 
         * REST Service
         * Get all signs (should be restricted to superuser) 
         * FIXME: We may consider to seach signs within a particular area and below 
         *    particular zoom levels to limit the amount. 
         ****************************************************************************/
         
        a.get("/signs", (ctx) -> {
            var type = ctx.queryParam("type");
            var uid = ctx.queryParam("user");
            
            List<SignInfo> res = new ArrayList<SignInfo>(); 
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                DbList<Sign> sgs = db.getAllSigns(
                    (type != null ? Integer.parseInt(type) : -1),
                    ("true".equals(uid) && auth.userid != null ? auth.userid : null)
                ); 
                db.commit();
                for (Sign x : sgs)
                    res.add(new SignInfo(x.getId(), x.getUrl(), x.getDescr(), x.getIcon(), 
                        x.getScale(), x.getCategory(), x.getGroup(), x.getPosition() ) );    
                ctx.json(res);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /signs: SQL error:"+e.getMessage(), 500, null);
            }
            finally { 
                db.close(); 
            }

        });
        
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Add a sign.
         ****************************************************************************/
         
        a.post("/signs", (ctx) -> {
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }    
            
            /* Get tracker info from request */
            SignInfo sc = (SignInfo) 
                ServerBase.fromJson(ctx.body(), SignInfo.class);
            if (sc==null) 
                ABORT(ctx, db, "POST /signs: cannot parse input", 
                    400, "Cannot parse input");
                        
            /* Database transaction */
            else try {
                LatLng ref = new LatLng(sc.pos[1], sc.pos[0]);
                String id = db.addSign(_myCall, sc.scale, sc.icon, sc.url, sc.descr, ref, sc.type, auth.userid);
                sc.id=id;
                db.commit();  
                _psub.put("sign", null, auth.userid);
                _dbp.getSync().localUpdate("signs", id, auth.userid, "ADD", ServerBase.toJson(sc));
                ctx.result(id); 
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /signs: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        } );
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Update a sign.
         ****************************************************************************/
         
        a.put("/signs/{id}", (ctx) -> {
            String ident = ctx.pathParam("id");

            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                Sign x = db.getSign(ident);
                if (x==null) {
                    ABORT(ctx, db, "PUT /signs/*: Object not found: "+ident,
                        404, "Object not found: "+ident);
                    return;
                }
                
                /* Get tracker info from request */
                SignInfo sc = (SignInfo) 
                    ServerBase.fromJson(ctx.body(), SignInfo.class);
                if (sc==null) { 
                    ABORT(ctx, db, "PUT /signs: cannot parse input", 
                        500, "Cannot parse input");        
                    return;
                }
                
                LatLng ref = new LatLng(sc.pos[1], sc.pos[0]);
                Sign s= db.getSign(ident);
                String uid = s.getUser();
                uid=(uid==null ? auth.userid : uid);
                
                db.updateSign(ident, sc.scale, sc.icon, sc.url, sc.descr, 
                    ref, sc.type, uid);        
                sc.id=ident;
                db.commit();               
                _psub.put("sign", null, auth.userid);
                _dbp.getSync().localUpdate("signs", ident, uid, "UPD", ServerBase.toJson(sc));
                ctx.result("Ok");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "PUT /signs/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}  
        });
            
            
            
        
        /**************************************************************************
         * REST Service: 
         * Delete a sign. 
         **************************************************************************/
         
        a.delete("/signs/{id}", (ctx) -> {
            String ident = ctx.pathParam("id");
            
            /* FIXME: Only owners or superuser may delete signs */
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
                
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                db.deleteSign(ident);
                db.commit();          
                _psub.put("sign", null, auth.userid);
                _dbp.getSync().localUpdate("signs", ident, "", "DEL", "");
                ctx.result("OK");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "DELETE /signs/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}  
        } );
        
        
        
        
        
        
        
        
    }


    

}
