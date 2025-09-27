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
import java.awt.Image;
import java.awt.image.*;
import javax.imageio.*;
import no.polaric.aprsd.filter.*;



 
public class PhotoApi extends ServerBase 
{
    private ServerConfig _api; 
    private PluginApi _dbp;    
    private ServerConfig.PubSub _psub;
    private String    _myCall;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public PhotoApi(ServerConfig api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        _myCall = api.getProperty("default.mycall", "NOCALL").toUpperCase();  
    }
        
            
        
    public static class PhotoInfo {
        public String id; 
        public Date time;
        public String userid; 
        public String descr;
        public double[] pos;
        public byte[] image; 
        
        public PhotoInfo() {}
        public PhotoInfo(String i, double[] p, Date t, String uid, String d, byte[] img)
            { id=i; pos=p; time=t;userid=uid;descr=d;image=img; }   
        
        public PhotoInfo(String i, LatLng p, Date t, String uid, String d, byte[] img) { 
           var apos = new double[] {p.getLng(), p.getLat()};
           id=i; pos=apos; time=t;userid=uid;descr=d;image=img; 
        }
    }
    
    
    public byte[] scaleImg(byte[] data, int max, String id) {
        SignsDBSession db = null;
        try {
            System.out.println("*** Image data length: "+data.length);
            if (data.length < 1100000)
                return data;
            
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            int h = img.getHeight();
            int w = img.getWidth();
                
            float ratio = (h > w ? max/(float) h : max/(float) w);
            h = (int) ((float) h * ratio);
            w = (int) ((float) w * ratio);
            Image img2 = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.getGraphics().drawImage(img2, 0, 0 , null);

            ByteArrayOutputStream outs = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", outs); 
            outs.flush();
            byte[] outd = outs.toByteArray();
            if (id != null) {
                 db = new SignsDBSession(_dbp.getDB());
                 db.updatePhotoImg(id, outd);
                 db.commit();
            }
            return outd;
        } 
        catch (Exception e) {
            e.printStackTrace(System.out);
            return null;
        }
        finally {
            if (db != null) 
                db.close();
        }
    }
    
    
      
    /** 
     * Return an error status message to client 
     */
    public Object ERROR(Context ctx, int status, String msg)
      { ctx.status(status); ctx.result(msg); return null;}
    
      
      
    public Object ABORT(Context ctx, SignsDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("PhotoApi", logmsg);
        if (db!=null)
            db.abort();
        return ERROR(ctx, status, msg);
    }
        
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        protect("/photos");
        protect("/photos/*");
                
        _psub = (ServerConfig.PubSub) _api.getWebserver().pubSub();
        _psub.createRoom("photo", (Class) null); 
                 
                 
        /***************************************************************************** 
         * REST Service
         * Get a list of users (and readonly attribute) with which the given object 
         * is shared.  
         *****************************************************************************/
         
        a.get("/photos/{id}/share", (ctx) -> {
            var id = ctx.pathParam("id");
                      
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                var tr =  db.getPhotoUsers(id);
                List<JsObject.User> usr = tr.toList();
                db.commit();
                ctx.json(usr);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /photos/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "GET /photos/*/share: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }
        });         
                 
                                 
                                 
        /***************************************************************************** 
         * REST Service
         * Add a user or group that share this object.  
         *****************************************************************************/
         
        a.post("/photos/{id}/share", (ctx) -> {         
            var id = ctx.pathParam("id");
            var auth = getAuthInfo(ctx); 
            
            /* Get user info from request */
            var u = (JsObject.User) 
                ServerBase.fromJson(ctx.body(), JsObject.User.class);
        
            if (u.userid.matches("[@#].+") && !auth.operator && !auth.admin && !u.userid.matches("@"+auth.group) ) {
                ERROR(ctx, 401, "You are not authorized to share with "+u.userid);
                return;
            }
            
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {                 
                if (u==null) { 
                    ABORT(ctx, db, "POST /photos/*/share: cannot parse input", 
                        500, "Cannot parse input");     
                    return;
                }
                db.sharePhoto(id, auth.userid,  u.userid, u.readOnly);
                
                /* Notify receiving user */
                if (!u.userid.matches("(#ALL)|(@.+)")) {
                    _psub.put("sharing", null, u.userid);
                    _api.getWebserver().notifyUser(u.userid, 
                        new ServerConfig.Notification("system", "share", 
                            auth.userid+" shared photo with you" , new Date(), 4));
                }
                
                db.commit();
                ctx.result("Ok");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /photos/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }        
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "POST /photos/*/share: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }   
        });
              
        
        
        /***************************************************************************** 
         * REST Service
         * Remove a user that share this object.  
         * return number of actual objects removed from database. 
         *****************************************************************************/
         
        a.delete("/photos/{id}/share/{uid}", (ctx) -> {         
            var id = ctx.pathParam("id");
            var uid = ctx.pathParam("uid");
            var auth = getAuthInfo(ctx); 
                        
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {  
                int n = 0;
 
                n = db.unlinkPhoto(id, auth.userid, uid);
                /* Notify receiving user */
                _psub.put("sharing", null, uid);
                
                db.commit();    
                ctx.result(""+n);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /photos/*/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }        
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "POST /photos/*/*/share: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }   
        });
        
        
        /**************************************************************************** 
         * REST Service
         * get a specific photo
         ****************************************************************************/
         
        a.get("/photos/{id}", (ctx) -> {
            var ident = ctx.pathParam("id");
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                Photo p = db.getPhoto(ident, auth.userid, auth.groupid);
                db.commit();
                ctx.json(new PhotoInfo(p.getId(), p.getPosition(), p.getTime(), p.getUser(), p.getDescr(), 
                    scaleImg(p.getImage(), 1900, p.getId())));
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET/photos/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }        
            catch (Exception e) {
                e.printStackTrace(System.out);
                ABORT(ctx, db, "GET/photos/*: Error:"+e.getMessage(),
                    500, "Error: "+e.getMessage());
            }
            finally { db.close();}  
        });
                
                
                
        a.get ("/open/photos/{id}", (ctx) -> {
            String ident = ctx.pathParam("id");
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                Photo p = db.getPhoto(ident, null, null);
                db.commit();
                ctx.json(new PhotoInfo(p.getId(), p.getPosition(), p.getTime(), p.getUser(), p.getDescr(), 
                    scaleImg(p.getImage(), 1900, p.getId())));
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET/photos/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }        
            catch (Exception e) {
                e.printStackTrace(System.out);
                ABORT(ctx, db, "GET/photos/*: Error:"+e.getMessage(),
                    500, "Error: "+e.getMessage());
            }
            finally { db.close();}  
        });
        


            
        
        /**************************************************************************** 
         * REST Service: 
         * Add a photo
         ****************************************************************************/
         
        a.post("/photos", (ctx) -> {
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            
            /* Get photo info from request */
            PhotoInfo p = (PhotoInfo) 
                ServerBase.fromJson(ctx.body(), PhotoInfo.class);
            if (p==null) { 
                ABORT(ctx, db, "POST /photos: cannot parse input", 
                    400, "Cannot parse input");
                return;
            }
            var auth = getAuthInfo(ctx); 
            if (auth == null)
                ERROR(ctx, 500, "No authorization info found");
                
            /* Database transaction */
            else try {
                if (p.time==null)
                    p.time=new Date();
                var id = db.addPhoto(_myCall, new LatLng(p.pos[1], p.pos[0]), auth.userid, p.time, p.descr, 
                     scaleImg(p.image, 1900, p.id) ); 
                db.commit();  
                ctx.result(id); 
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /photos: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        } );
        
        
        
        /**************************************************************************
         * REST Service: 
         * Delete a photo. 
         **************************************************************************/
         
        a.delete("/photos/{id}", (ctx) -> {
            var ident = ctx.pathParam("id");
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return; 
            }
            
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                db.unlinkPhoto(ident, auth.userid, auth.userid);
                db.commit();         
                ctx.result("OK");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "DELETE /photos/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e. getMessage());
            }
            finally { db.close();}  
        } );
        
        
        
        a.put("/photos/*/descr", (ctx) -> {
            var ident = ctx.pathParam("id");
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            
            String descr = (String) 
                ServerBase.fromJson(ctx.body(), String.class);
                
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                db.updatePhotoDescr(ident, auth.userid, descr);
                db.commit();          
                ctx.result("OK");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "PUT /photos/*/descr: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e. getMessage());
            }
            finally { db.close();}  
        } );
        
    }
}
