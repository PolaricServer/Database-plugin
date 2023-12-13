
package no.polaric.aprsdb.http;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
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
        _api.getWebserver().protectUrl("/photos");
        _api.getWebserver().protectUrl("/photos/*");
                
        _psub = (no.polaric.aprsd.http.PubSub) _api.getWebserver().getPubSub();
        _psub.createRoom("photo", (Class) null); 
                        
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
                Photo p = db.getPhoto(ident, auth.userid);
                db.commit();
                return new PhotoInfo(p.getId(), p.getPosition(), p.getTime(), auth.userid, p.getDescr(), p.getImage());
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET/photos/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }        
            catch (Exception e) {
                e.printStackTrace(System.out);
                return ABORT(resp, db, "GET/photos/*: Drror:"+e.getMessage(),
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
                var id = db.addPhoto(_myCall, new LatLng(p.pos[1], p.pos[0]), auth.userid, p.time, p.descr, p.image ); 
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
                db.deletePhoto(ident, auth.userid);
                db.commit();          
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /photos/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e. getMessage());
            }
            finally { db.close();}  
        } );
        
        
        
    }
}
