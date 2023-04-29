
package no.polaric.aprsdb.http;
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


/*
 * 
 */
 
public class SignsApi extends ServerBase implements JsonPoints
{
    private ServerAPI _api; 
    private PluginApi _dbp;    
    private PubSub _psub;
    private String    _myCall;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public SignsApi(ServerAPI api) {
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
        public SignInfo(String i, String u, String d, String ic, long sc, int t, String tn, Reference p)
            {   
                id=i;url=u; descr=d; icon=ic; scale=sc; type=t; tname=tn;
                LatLng pp = p.toLatLng(); 
                pos = new double[2]; 
                pos[0] = pp.getLongitude(); 
                pos[1] = pp.getLatitude();
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
        _api.getWebserver().corsEnable("/signs");
        _api.getWebserver().corsEnable("/signs/*");
        _api.getWebserver().protectUrl("/signs", "sar");
        _api.getWebserver().protectUrl("/signs/*", "sar");
                
        _psub = (no.polaric.aprsd.http.PubSub) _api.getWebserver().getPubSub();
        
        
        _psub.createRoom("sign", (Class) null); 
                
        /**************************************************************************** 
         * REST Service
         * get a list of types (categories)
         ****************************************************************************/
         
        get("/signs/types", "application/json", (req, resp) -> {
            List<Sign.Category> res = new ArrayList<Sign.Category>(); 
            /* Database transaction */
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                DbList<Sign.Category> rr = db.getCategories();
                for (Sign.Category x : rr)
                    res.add(x);
                db.commit(); 
                return res;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /signs/types: SQL error:"+e.getMessage(), 500, null);
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
                        
        /**************************************************************************** 
         * REST Service
         * get a specific sign
         ****************************************************************************/
         
        get ("/signs/*", "application/json", (req, resp) -> {
            String ident = req.splat()[0];
            
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                Sign x = db.getSign(ident);
                if (x==null)
                    return ABORT(resp, db, "GET /signs/*: Object not found: "+ident,
                        404, "Object not found: "+ident);
                db.commit();
                SignInfo s = new SignInfo(x.getId(), x.getUrl(), x.getDescr(), x.getIcon(), 
                        x.getScale(), x.getCategory(), x.getGroup(), x.getPosition() ); 
                return s;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET/signs/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}  
        }, ServerBase::toJson);
        
        
        
        /**************************************************************************** 
         * REST Service
         * Get all signs (should be restricted to superuser) 
         * FIXME: We may consider to seach signs within a particular area and below 
         *    particular zoom levels to limit the amount. 
         ****************************************************************************/
         
        get("/signs", "application/json", (req, resp) -> {
            var type = req.queryParams("type");
            var uid = req.queryParams("user");
            
            List<SignInfo> res = new ArrayList<SignInfo>(); 
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
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
                return res;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /signs: SQL error:"+e.getMessage(), 500, null);
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Add a sign.
         ****************************************************************************/
         
        post("/signs", (req, resp) -> {
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");;
                
            /* Get tracker info from request */
            SignInfo sc = (SignInfo) 
                ServerBase.fromJson(req.body(), SignInfo.class);
            if (sc==null) 
                return ABORT(resp, db, "POST /signs: cannot parse input", 
                    400, "Cannot parse input");
                        
            /* Database transaction */
            try {
                Reference ref = new LatLng(sc.pos[1], sc.pos[0]);
                String id = db.addSign(_myCall, sc.scale, sc.icon, sc.url, sc.descr, ref, sc.type, auth.userid);
                sc.id=id;
                db.commit();  
                _psub.put("sign", null, auth.userid);
                _dbp.getSync().localUpdate("signs", id, auth.userid, "ADD", ServerBase.toJson(sc));
                return id; 
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /signs: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        } );
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Update a sign.
         ****************************************************************************/
         
        put("/signs/*", (req, resp) -> {
            String ident = req.splat()[0];

            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                Sign x = db.getSign(ident);
                if (x==null)
                    return ABORT(resp, db, "PUT /signs/*: Object not found: "+ident,
                        404, "Object not found: "+ident);
                
                /* Get tracker info from request */
                SignInfo sc = (SignInfo) 
                    ServerBase.fromJson(req.body(), SignInfo.class);
                if (sc==null) 
                    return ABORT(resp, db, "PUT /signs: cannot parse input", 
                        500, "Cannot parse input");        
                        
                Reference ref = new LatLng(sc.pos[1], sc.pos[0]);
                
                Sign s= db.getSign(ident);
                String uid = s.getUser();
                uid=(uid==null ? auth.userid : uid);
                
                db.updateSign(ident, sc.scale, sc.icon, sc.url, sc.descr, 
                    ref, sc.type, uid);        
                sc.id=ident;
                db.commit();               
                _psub.put("sign", null, auth.userid);
                _dbp.getSync().localUpdate("signs", ident, uid, "UPD", ServerBase.toJson(sc));
                return "Ok";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "PUT /signs/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}  
        });
            
            
            
        
        /**************************************************************************
         * REST Service: 
         * Delete a sign. 
         **************************************************************************/
         
        delete("/signs/*", (req, resp) -> {
            String ident = req.splat()[0];
            
            /* FIXME: Only owners or superuser may delete signs */
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
                
                
            SignsDBSession db = new SignsDBSession(_dbp.getDB());
            try {
                db.deleteSign(ident);
                db.commit();          
                _psub.put("sign", null, auth.userid);
                _dbp.getSync().localUpdate("signs", ident, "", "DEL", "");
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /signs/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}  
        } );
        
        
        
        
        
        
        
        
    }


    

}
