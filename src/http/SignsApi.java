
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
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public SignsApi(ServerAPI api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
    }
        
        
        
    public static class SignInfo {
        public int id; 
        public String url;
        public String descr;
        public String icon;
        public long scale;
        public int type;
        public String tname;
        public double[] pos;
        public SignInfo() {}
        public SignInfo(int i, String u, String d, String ic, long sc, int t, String tn, Reference p)
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
      
      
      
    public String ABORT(Response resp, MyDBSession db, String logmsg, int status, String msg) {
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
                
        
        
                
        /**************************************************************************** 
         * REST Service
         * get a list of types (categories)
         ****************************************************************************/
         
        get("/signs/types", "application/json", (req, resp) -> {
            List<Sign.Category> res = new ArrayList(); 
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
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
            
            MyDBSession db = _dbp.getDB();
            try {
                Sign x = db.getSign(Integer.parseInt(ident));
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
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "GET /signs/*: ident must be a positive integer",
                    400, "ident must be a positive integer");
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
            MyDBSession db = _dbp.getDB();
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
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "DELETE /signs: type must be a positive integer",
                    400, "ident must be a positive integer");
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
            MyDBSession db = _dbp.getDB();
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");;
                
            /* Get tracker info from request */
            SignInfo sc = (SignInfo) 
                ServerBase.fromJson(req.body(), SignInfo.class);
            if (sc==null) 
                return ABORT(resp, db, "POST /signs: cannot parse input", 
                    500, "Cannot parse input");
                        
            /* Database transaction */
            try {
                Reference ref = new LatLng(sc.pos[1], sc.pos[0]);
                int id = db.addSign(sc.scale, sc.icon, sc.url, sc.descr, ref, sc.type, auth.userid);
                db.commit();
                return ""+id; 
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
            
            MyDBSession db = _dbp.getDB();
            try {
                Sign x = db.getSign(Integer.parseInt(ident));
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
                db.updateSign(Integer.parseInt(ident), sc.scale, sc.icon, sc.url, sc.descr, 
                    ref, sc.type, auth.userid);        
                        
                db.commit();
                return "Ok";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "PUT /signs/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "PUT /signs/*: ident must be a positive integer",
                    400, "ident must be a positive integer");
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
            
            MyDBSession db = _dbp.getDB();
            try {
                db.deleteSign(Integer.parseInt(ident));
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /signs/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "DELETE /signs/*: ident must be a positive integer",
                    400, "ident must be a positive integer");
            }
            finally { db.close();}  
        } );
        
        
        
        
        
        
        
        
    }


    

}
