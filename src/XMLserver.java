
package no.polaric.aprsdb;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;
import org.simpleframework.http.core.Container;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.io.PrintStream;

import uk.me.jstott.jcoord.*;
import java.util.*;
import java.io.*;
import java.text.*;
import com.mindprod.base64.Base64;
import java.util.concurrent.locks.*; 


public class XMLserver extends ServerBase
{
   private DatabasePlugin _dbp;
   private String _icon;
   
   public XMLserver(ServerAPI api) throws IOException
   {
      super(api);
      _icon = api.getConfig().getProperty("map.icon.default", "sym.gif").trim();
      _dbp = (DatabasePlugin) api.properties().get("aprsdb.plugin");
   }


   private int _seq = 0;
   
   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    */
   public void handle_htrail(Request req, Response res) 
      throws IOException
   {         
        PrintWriter out = getWriter(res);
        res.setValue("Content-Type", "text/xml; charset=utf-8");
                
        /* FIXME: Identical code in aprsd core */
        UTMRef uleft = null, lright = null;
        Query parms = req.getQuery();
        if (parms.get("x1") != null) {
          long x1 = Long.parseLong( parms.get("x1") );
          long x2 = Long.parseLong( parms.get("x2") );
          long x3 = Long.parseLong( parms.get("x3") );    
          long x4 = Long.parseLong( parms.get("x4") );
          uleft = new UTMRef((double) x1, (double) x2, _utmlatzone, _utmzone); 
          lright = new UTMRef((double) x3, (double) x4, _utmlatzone, _utmzone);
        }
        long scale = 0;
        if (parms.get("scale") != null)
           scale = Long.parseLong(parms.get("scale"));
        

        boolean showSarInfo = (getAuthUser(req) != null || _api.getSar() == null);
        long client = getSession(req);
                
                
        /* XML header with meta information */      
        out.println("<overlay seq=\"-1\">");
        printXmlMetaTags(out, req);
        out.println("<meta name=\"clientses\" value=\""+ client + "\"/>");

        MyDBSession db = _dbp.getDB();
        try {    
          String src = parms.get("station");   
          java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");

          Date dfrom = df.parse(parms.get("tfrom"));
          Date dto = null; 
          if (parms.get("tto").equals("-/-"))
             dto = new Date(); 
          else
             dto = df.parse(parms.get("tto"));
             
          Station s = (Station) db.getItem(src, dto);
          DbList<TPoint> h = db.getTrail(src, dfrom, dto);
          
          TPoint first = h.next();
          if (first != null) {    
              UTMRef ref = toUTM(first.getPosition()); 
              String title = s.getDescr() == null ? "" 
                      : "title=\"[" + fixText(s.getIdent()) + "] " + fixText(s.getDescr()) + "\"";
              String icon = _wfiledir + "/icons/"+ (s.getIcon() != null ? s.getIcon() : _icon);    
         
              out.println("<point id=\""+fixText(s.getIdent())+"\" x=\""
                        + (int) Math.round(ref.getEasting()) + "\" y=\"" + (int) Math.round(ref.getNorthing())+ "\" " 
                        + title + (s.isChanging() ? " redraw=\"true\"" : "") + ">");
              out.println("   <icon src=\""+icon+"\" w=\"22\" h=\"22\" ></icon>");     
              out.println("   <label style=\"lmoving\">");
              out.println("       "+fixText(s.getDisplayId(showSarInfo)));
              out.println("   </label>");  
              h.reset();    
              printTrailXml(out, s.getTrailColor(), h.next().getPosition(), h, uleft, lright);
              
              out.println("</point>");   
          }        
          db.commit();
        }
        catch(java.text.ParseException e)
          { System.out.println("*** WARNING: Cannot parse timestring"); db.abort(); }
        catch(java.sql.SQLException e)
          { System.out.println("*** WARNING: SQL error:"+e.getMessage()); db.abort(); }
        finally { db.close(); }
        
        out.println("</overlay>");
        out.close();
   }

   
   
   
   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    */
   
   /* FIXME: This has many similarities with handle_htrail  */
  
   public void handle_hpoints(Request req, Response res) 
      throws IOException
   {         
        PrintWriter out = getWriter(res);
        res.setValue("Content-Type", "text/xml; charset=utf-8");
                
        /* FIXME: Identical code in aprsd core */
        UTMRef uleft = null, lright = null;
        Query parms = req.getQuery();
        if (parms.get("x1") != null) {
          long x1 = Long.parseLong( parms.get("x1") );
          long x2 = Long.parseLong( parms.get("x2") );
          long x3 = Long.parseLong( parms.get("x3") );    
          long x4 = Long.parseLong( parms.get("x4") );
          uleft = new UTMRef((double) x1, (double) x2, _utmlatzone, _utmzone); 
          lright = new UTMRef((double) x3, (double) x4, _utmlatzone, _utmzone);
        }
        long scale = 0;
        if (parms.get("scale") != null)
           scale = Long.parseLong(parms.get("scale"));
        

        boolean showSarInfo = (getAuthUser(req) != null || _api.getSar() == null);
        long client = getSession(req);
                
                
        /* XML header with meta information */      
        out.println("<overlay seq=\"-1\">");
        printXmlMetaTags(out, req);
        out.println("<meta name=\"clientses\" value=\""+ client + "\"/>");

        MyDBSession db = _dbp.getDB();
        try {    
          String src = parms.get("station");   
          java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");

          Date dfrom = df.parse(parms.get("tfrom"));
          Date dto = null; 
          if (parms.get("tto").equals("-/-"))
             dto = new Date(); 
          else
             dto = df.parse(parms.get("tto"));
             
          Station s = (Station) db.getItem(src, dto);
          if (s != null) {    
              DbList<TPoint> h = db.getPointsVia(src, uleft, lright, dfrom, dto);
              UTMRef ref = toUTM(s.getPosition()); 
              String title = s.getDescr() == null ? "" 
                      : "title=\"[" + fixText(s.getIdent()) + "] " + fixText(s.getDescr()) + "\"";
              String icon = _wfiledir + "/icons/"+ (s.getIcon() != null ? s.getIcon() : _icon);    
         
              out.println("<point id=\""+fixText(s.getIdent())+"\" x=\""
                        + (int) Math.round(ref.getEasting()) + "\" y=\"" 
                        + (int) Math.round(ref.getNorthing())+ "\" " 
                        + title + (s.isChanging() ? " redraw=\"true\"" : "") + ">");
              out.println("   <icon src=\""+icon+"\" w=\"22\" h=\"22\" ></icon>");     
              out.println("   <label style=\"lmoving\">");
              out.println("       "+fixText(s.getDisplayId(showSarInfo)));
              out.println("   </label>");  
              h.reset();    
                           
              printPointCloud(out, "1100ee", h, uleft, lright);
              
              out.println("</point>");   
          }        
          db.commit();
        }
        catch(java.text.ParseException e)
          { System.out.println("*** WARNING: Cannot parse timestring"); db.abort(); }
        catch(java.sql.SQLException e)
          { System.out.println("*** WARNING: SQL error:"+e.getMessage()); db.abort(); }
        finally { db.close(); }
        
        out.println("</overlay>");
        out.close();
   }     
   

}
