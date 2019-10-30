
package no.polaric.aprsdb;
import no.polaric.aprsd.*;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.http.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.io.PrintStream;

import uk.me.jstott.jcoord.*;
import java.util.*;
import java.io.*;
import java.text.*;
import com.mindprod.base64.Base64;
import java.util.concurrent.locks.*; 
import spark.Request;
import spark.Response;
import spark.QueryParamsMap;




public class XMLserver extends ServerBase
{
   private PluginApi _dbp;
   private String _icon;
   
   public XMLserver(ServerAPI api) throws IOException
   {
      super(api);
      _icon = api.getConfig().getProperty("map.icon.default", "sym.gif").trim();
      _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
   }


   private int _seq = 0;
   
    
   
   
   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    */
   public String handle_htrail(Request req, Response res) 
      throws IOException
   {         
        StringWriter outs = new StringWriter();
        PrintWriter out = new PrintWriter(outs, false);

        res.header("Content-Type", "text/xml; charset=utf-8");
                
        QueryParamsMap parms = req.queryMap();

        if (parms.value("x1") == null) 
           return "";
        
        Double x1 = Double.parseDouble( parms.value("x1") );
        Double x2 = Double.parseDouble( parms.value("x2") );
        Double x3 = Double.parseDouble( parms.value("x3") );    
        Double x4 = Double.parseDouble( parms.value("x4") );
        final LatLng uleft  = new LatLng((double) x4, (double) x1); 
        final LatLng lright = new LatLng((double) x2, (double) x3);

        long scale = 0;
        if (parms.value("scale") != null)
           scale = Long.parseLong(parms.value("scale"));
        
        boolean showSarInfo = (getAuthInfo(req).userid != null || _api.getSar() == null);
                    
        out.println("<overlay seq=\"-1\">");

        MyDBSession db = _dbp.getDB();
        try {    
          String src = parms.value("station").toUpperCase();   
          java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");

          Date dfrom = df.parse(parms.value("tfrom"));
          Date dto = null; 
          if (parms.value("tto").equals("-/-"))
             dto = new Date(); 
          else
             dto = df.parse(parms.value("tto"));
             
          Station s = (Station) db.getItem(src, dto);
          DbList<TPoint> h = db.getTrail(src, dfrom, dto);
          
          TPoint first = h.next();
          if (first != null) {    
              LatLng ref = first.getPosition().toLatLng();
              String title = s.getDescr() == null ? "" 
                      : "title=\"[" + fixText(s.getIdent()) + "] " + fixText(s.getDescr()) + "\"";
              String icon = _wfiledir + "/icons/"+ (s.getIcon() != null ? s.getIcon() : _icon);    
         
              out.println("<point id=\""+fixText(s.getIdent())+"\" x=\""
                        + roundDeg(ref.getLng()) + "\" y=\"" + roundDeg(ref.getLat()) + "\" " 
                        + title + (s.isChanging() ? " redraw=\"true\"" : "") + ">");
              out.println("   <icon src=\""+icon+"\" w=\"22\" h=\"22\" ></icon>");     
              out.println("   <label style=\"lmoving\">");
              out.println("       "+fixText(s.getDisplayId(showSarInfo)));
              out.println("   </label>");  
              h.reset();    
              printTrailXml(out, s.getTrailColor(), h.next().getPosition(), 
                    new Seq.Wrapper<TPoint>(h, tp -> tp.isInside(uleft, lright, 0.7, 0.7)));
              out.println("</point>");   
          }
          else
              _dbp.log().info("XMLserver", "Htrail search returned empty result: "+src);
          db.commit();
        }
        catch(java.text.ParseException e)
          { _dbp.log().warn("XMLserver", "handle_htrail: Cannot parse timestring"); db.abort(); }
        catch(java.sql.SQLException e)
          { _dbp.log().warn("XMLserver", "handle_htrail: SQL error:"+e.getMessage()); db.abort(); }
        finally { db.close(); }
        
        out.println("</overlay>");
        out.close();
        return outs.toString();
   }

   
   
   
   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    */
   
   /* FIXME: This has many similarities with handle_htrail  */
  
   public String handle_hpoints(Request req, Response res) 
      throws IOException
   {         
        StringWriter outs = new StringWriter();
        PrintWriter out = new PrintWriter(outs, false);
        
        res.header("Content-Type", "text/xml; charset=utf-8");
                
        QueryParamsMap parms = req.queryMap();

        if (parms.value("x1") == null) 
            return "";
        Double x1 = Double.parseDouble( parms.value("x1") );
        Double x2 = Double.parseDouble( parms.value("x2") );
        Double x3 = Double.parseDouble( parms.value("x3") );    
        Double x4 = Double.parseDouble( parms.value("x4") );
        final LatLng uleft  = new LatLng((double) x4, (double) x1); 
        final LatLng lright = new LatLng((double) x2, (double) x3);

        long scale = 0;
        String color = "1100ee";
        
        if (parms.value("scale") != null)
           scale = Long.parseLong(parms.value("scale"));
        if (parms.value("color") != null)
           color = parms.value("color");
           
        boolean showSarInfo = (getAuthInfo(req).userid != null || _api.getSar() == null);     
                   
        out.println("<overlay seq=\"-1\">");

        MyDBSession db = _dbp.getDB();
        try {    
          String src = parms.value("station");   
          java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd");

          Date dfrom = df.parse(parms.value("tfrom"));
          Date dto = null; 
          if (parms.value("tto").equals("-"))
             dto = new Date(); 
          else
             dto = df.parse(parms.value("tto"));
             
          Station s = (Station) db.getItem(src, dto);
          if (s != null) {  
              if (s.getPosition() != null) {
                 LatLng ref = s.getPosition().toLatLng(); 
                 DbList<TPoint> h = db.getPointsVia(src, uleft, lright, dfrom, dto);        
                 String title = s.getDescr() == null ? "" 
                      : "title=\"[" + fixText(s.getIdent()) + "] " + fixText(s.getDescr()) + "\"";
                       
                 String icon = _wfiledir + "/icons/"+ (s.getIcon(showSarInfo) != null ? s.getIcon(showSarInfo) : _icon);   
          
                 out.println("<point id=\""+fixText(s.getIdent())+"\" x=\""
                        + roundDeg(ref.getLng()) + "\" y=\"" 
                        + roundDeg(ref.getLat()) + "\" " 
                        + title + (s.isChanging() ? " redraw=\"true\"" : "") + ">");
                 out.println("   <icon src=\""+icon+"\" w=\"22\" h=\"22\" ></icon>");     
                 out.println("   <label style=\"lmoving\">");
                 out.println("       "+fixText(s.getDisplayId(showSarInfo)));
                 out.println("   </label>");  
                 h.reset();    
                           
                 printPointCloud(out, color, new Seq.Wrapper<TPoint>(h, tp -> tp.isInside(uleft, lright, 0.7, 0.7)));
                 out.println("</point>");   
              }
              else
                 _dbp.log().warn("XMLserver", "handle_hpoints: Point has no position: "+src);
          }        
          else
              _dbp.log().info("XMLserver", "Hpoints search returned empty result: "+src);
          db.commit();
        }
        catch(java.text.ParseException e)
          { _dbp.log().warn("XMLserver", "handle_hpoints: Cannot parse timestring"); db.abort(); }
        catch(java.sql.SQLException e)
          { _dbp.log().warn("XMLserver",  "handle_hpoints: SQL error:"+e.getMessage()); db.abort(); }
        finally { db.close(); }
        
        out.println("</overlay>");
        out.close();
        return outs.toString();
   }     
   

}
