
package no.polaric.aprsdb.http;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.awt.Image;
import java.awt.image.*;
import javax.imageio.*;
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


/*
 * 
 */
 
public class PhotoApi extends ServerBase 
{
    private ServerAPI _api; 
    private PluginApi _dbp;    
    private PubSub _psub;
    private String    _myCall;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public PhotoApi(ServerAPI api) {
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
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
      
    public String ABORT(Response resp, SignsDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("RestApi", logmsg);
        db.abort();
        return ERROR(resp, status, msg);
    }
      


    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        _api.getWebserver().corsEnable("/photos");
        _api.getWebserver().corsEnable("/photos/*");
        _api.getWebserver().corsEnable("/open/photos/*");
        _api.getWebserver().protectUrl("/photos");
        _api.getWebserver().protectUrl("/photos/*");
                
        _psub = (no.polaric.aprsd.http.PubSub) _api.getWebserver().getPubSub();
        _psub.createRoom("photo", (Class) null); 
                 
                 
        /***************************************************************************** 
         * REST Service
         * Get a list of users (and readonly attribute) with which the given object 
         * is shared.  
         *****************************************************************************/
         
        get("/photos/*/share", "application/json", (req, resp) -> {
            String id = req.splat()[0];
          
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                var tr =  db.getPhotoUsers(id);
                List<JsObject.User> usr = tr.toList();
                db.commit();
                return usr;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /photos/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "GET /photos/*/share: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }
        }, ServerBase::toJson );         
                 
                                 
                                 
        /***************************************************************************** 
         * REST Service
         * Add a user or group that share this object.  
         *****************************************************************************/
         
        post("/photos/*/share", (req, resp) -> {         
            String id = req.splat()[0];
            var auth = getAuthInfo(req); 
            
            /* Get user info from request */
            var u = (JsObject.User) 
                ServerBase.fromJson(req.body(), JsObject.User.class);
        
            if (u.userid.matches("[@#].+") && !auth.sar && !auth.admin && !u.userid.matches("@"+auth.group) )
                return ERROR(resp, 401, "You are not authorized to share with "+u.userid);
        
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {                 
                if (u==null) 
                    return ABORT(resp, db, "POST /photos/*/share: cannot parse input", 
                        500, "Cannot parse input");     
                        
                db.sharePhoto(id, auth.userid,  u.userid, u.readOnly);
                
                /* Notify receiving user */
                if (!u.userid.matches("(#ALL)|(@.+)")) {
                    _psub.put("sharing", null, u.userid);
                    _api.getWebserver().notifyUser(u.userid, 
                        new ServerAPI.Notification("system", "share", 
                            auth.userid+" shared photo with you" , new Date(), 4));
                }
                
                db.commit();
                return "Ok";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /photos/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }        
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "POST /photos/*/share: Object id must be numeric", 400, "Object id must be numeric");
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
         
        delete("/photos/*/share/*", (req, resp) -> {         
            String id = req.splat()[0];
            String uid = req.splat()[1];
            var auth = getAuthInfo(req); 
                        
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {  
                int n = 0;
 
                n = db.unlinkPhoto(id, auth.userid, uid);
                /* Notify receiving user */
                _psub.put("sharing", null, uid);
                
                db.commit();    
                return ""+n;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /photos/*/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }        
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "POST /photos/*/*/share: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }   
        });
        
        
        /**************************************************************************** 
         * REST Service
         * get a specific photo
         ****************************************************************************/
         
        get ("/photos/*", "application/json", (req, resp) -> {
            String ident = req.splat()[0];
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");

            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                Photo p = db.getPhoto(ident, auth.userid, auth.groupid);
                db.commit();
                return new PhotoInfo(p.getId(), p.getPosition(), p.getTime(), p.getUser(), p.getDescr(), 
                    scaleImg(p.getImage(), 1900, p.getId()));
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET/photos/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }        
            catch (Exception e) {
                e.printStackTrace(System.out);
                return ABORT(resp, db, "GET/photos/*: Error:"+e.getMessage(),
                    500, "Error: "+e.getMessage());
            }
            finally { db.close();}  
        }, ServerBase::toJson);
                
                
                
        get ("/open/photos/*", "application/json", (req, resp) -> {
            String ident = req.splat()[0];
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                Photo p = db.getPhoto(ident, null, null);
                db.commit();
                return new PhotoInfo(p.getId(), p.getPosition(), p.getTime(), p.getUser(), p.getDescr(), 
                    scaleImg(p.getImage(), 1900, p.getId()));
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET/photos/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }        
            catch (Exception e) {
                e.printStackTrace(System.out);
                return ABORT(resp, db, "GET/photos/*: Error:"+e.getMessage(),
                    500, "Error: "+e.getMessage());
            }
            finally { db.close();}  
        }, ServerBase::toJson);
        


            
        
        /**************************************************************************** 
         * REST Service: 
         * Add a photo
         ****************************************************************************/
         
        post("/photos", (req, resp) -> {
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            
            /* Get photo info from request */
            PhotoInfo p = (PhotoInfo) 
                ServerBase.fromJson(req.body(), PhotoInfo.class);
            if (p==null) 
                return ABORT(resp, db, "POST /photos: cannot parse input", 
                    400, "Cannot parse input");
            
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
                
            /* Database transaction */
            try {
                if (p.time==null)
                    p.time=new Date();
                var id = db.addPhoto(_myCall, new LatLng(p.pos[1], p.pos[0]), auth.userid, p.time, p.descr, 
                     scaleImg(p.image, 1900, p.id) ); 
                db.commit();  
                return id; 
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /photos: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        } );
        
        
        
        /**************************************************************************
         * REST Service: 
         * Delete a photo. 
         **************************************************************************/
         
        delete("/photos/*", (req, resp) -> {
            String ident = req.splat()[0];
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
                     
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                db.unlinkPhoto(ident, auth.userid, auth.userid);
                db.commit();          
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /photos/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e. getMessage());
            }
            finally { db.close();}  
        } );
        
        
        
        put("/photos/*/descr", (req, resp) -> {
            String ident = req.splat()[0];
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            String descr = (String) 
                ServerBase.fromJson(req.body(), String.class);
                
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                db.updatePhotoDescr(ident, auth.userid, descr);
                db.commit();          
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "PUT /photos/*/descr: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e. getMessage());
            }
            finally { db.close();}  
        } );
        
    }
}
