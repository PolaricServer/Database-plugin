
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
 * This will eventually replace the XML service for trail and point cloud. 
 * see XMLserver.java
 */
 
public class GpxFileApi extends ServerBase implements JsonPoints
{
    private ServerAPI _api; 
    private PluginApi _dbp;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public GpxFileApi(ServerAPI api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
    }
        
        
    /** 
     * Return an error status message to client 
     */
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
      
    public String ABORT(Response resp, MyDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("GpxFileApi", logmsg);
        db.abort();
        return ERROR(resp, status, msg);
    }
      

    
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        _api.getWebserver().protectUrl("/files/*");
        _api.getWebserver().corsEnable("/files/*");
    
    
        
        
        /***************************************************************************** 
         * REST Service
         * Get content of a GPX file object for a given user. 
         *****************************************************************************/
         
        get("/files/gpx/*", "text/gpx+xml", (req, resp) -> {
            String fid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            try {
                resp.header("cache-control", "max-age=3600"); /* 1 hour cache */
                long id = Long.parseLong(fid); 
                var x = db.getFileObject(id);
                db.commit();
                return x;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /files/gpx/"+fid+": SQL error:"+e.getMessage(), 500, null);
            }
            catch (NumberFormatException e) {
                return ABORT(resp, db,  "GET /files/gpx/"+fid+": Last part of url should be number",
                    500, null);
            }
            finally { 
                db.close(); 
            }
        });
             
        
        /***************************************************************************** 
         * REST Service
         * Save content of a GPX file object for a given user. 
         *****************************************************************************/
        /* Note: This expects multipart form data */
        
        post("/files/gpx", (req, resp) -> {
            MyDBSession db = _dbp.getDB();
            req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement(""));
            
            try {        
                List<Long> ids = new ArrayList<Long>(); 
                java.util.Collection<Part> parts = req.raw().getParts(); 
                for (Part p: parts) {
                    var type = p.getContentType();
                    var file = p.getSubmittedFileName();
                    if (type != null && (
                        (type.equals("application/gpx+xml")) || type.equals("application/x-gpx+xml") ||
                        (file.substring(file.lastIndexOf(".")+1).equals("gpx"))
                    )) {
                        _dbp.log().info("RestApi", "Upload file: name="+file+", size="+p.getSize());
                        ids.add( db.addFileObject(p.getInputStream()) );
                        db.commit();
                    }
                    else
                        return ABORT(resp, db, "post/files/gpx/: Unsupported file type: "+type, 
                            415, "Unsupported file type (should be GPX)");
                }
                return ids;
            }
            catch(java.sql.SQLException e) {
                return ABORT(resp, db, "POST /files/gpx/: SQL error: "+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }      
            catch(Exception e) {
                e.printStackTrace(System.out);
                return ABORT(resp, db, "POST /files/gpx/: Strange server error: "+e.getMessage(),
                    500, "Server error: "+e.getMessage());
            }
            finally {
                db.close();
            }
        }, ServerBase::toJson );
        
        
        
        /***************************************************************************** 
         * REST Service
         * Delete a GPX file object. 
         *****************************************************************************/
        
        delete("/files/gpx/*", (req, resp) -> {
            String fid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            try {
                long id = Long.parseLong(fid); 
                db.deleteFileObject(id);
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /files/gpx/"+fid+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            catch (NumberFormatException e) {
                return ABORT(resp, db,  "DELETE /files/gpx/"+fid+": Last part of url should be number",
                    500, "Expected numeric input");
            }
            finally { 
                db.close(); 
            }
        });
        
        
    }

    

}
