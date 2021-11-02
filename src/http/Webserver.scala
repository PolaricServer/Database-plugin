 
import java.util._
import java.io._
import uk.me.jstott.jcoord._
import scala.xml._
import scala.collection.JavaConversions._
import no.polaric.aprsd._
import no.polaric.aprsdb._
import no.polaric.aprsd.http.ServerUtils
import no.polaric.aprsd.http.ServerBase
import no.polaric.aprsd.http.PointView
import no.polaric.aprsd.http.TrackerPointView
import spark.Request;
import spark.Response;



package no.polaric.aprsdb.http
{

  class Webserver 
      ( val api: ServerAPI ) extends ServerBase(api) with ServerUtils
  {
     
     
   val _dbp = api.properties().get("aprsdb.plugin").asInstanceOf[no.polaric.aprsdb.PluginApi];
   val dateformat = "(\\-\\/\\-)|([0-9]{4}\\-[01][0-9]\\-[0-3][0-9]\\/[0-2][0-9]:[0-5][0-9])"
   val sdf = new java.text.SimpleDateFormat("HH:mm")  
     
       
     
     
   /**
    * Webservice to export a set of tracks in GPX format. 
    * Request parameters tell what stations and time-periods to show: 
    *    @param ntracks number of tracks to show. 
    *    @param stationN ident (callsign) of station.
    *    @param dfromN   Time of start of track in yyy-MM-dd/HH:mm format
    *    @param dtoN     Time of end of track in yyy-MM-dd/HH:mm format
    * N is index from 0 to ntracks-1
    */
    
   def handle_gpx(req : Request, res : Response) = 
   {         
       val db = _dbp.getDB(true) 
       val xdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
       val df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm")
       xdf.setTimeZone(TimeZone.getTimeZone("GMT"))


       def do_trail(src:String, dfrom:Date, dto:Date) = 
       {
            val s = db.getItem(src, dto)
            val h = db.getTrail(src, dfrom, dto, false)
            _dbp.log().debug("Db.Webserver", "do_trail...")
            
            <trk>
                <name>{s.getIdent}</name>
                <trkseg>
                {
                   var pos:LatLng = null;
                   for (pt <- h.iterator; if pos==null || !pt.getPosition().toLatLng().equalPos(pos)) yield {
                      pos = pt.getPosition().toLatLng();                    
                      <trkpt lat={""+pos.getLatitude} lon={""+pos.getLongitude}>
                         <time>{xdf.format(pt.getTS)}</time>  
                      </trkpt>
                   }
                }
                </trkseg>
            </trk>
       }
   

        /* Get request parameters 
         * FIXME: Deal with parse errors (input parameters) !!!!!
         */         
        
        val ntracks = req.queryParams("ntracks").toInt
        var tracks = new Array [Tuple3[String, Date, Date]] (ntracks)
        
        for (i <- 0 to ntracks-1) { 
           val src = req.queryParams("station"+i)
           val dfrom = req.queryParams("tfrom"+i)
           val dto = req.queryParams("tto"+i)
           _dbp.log().debug("Db.Webserver", "GPX: dto = '"+dto+"'")
           
           if (dfrom.matches(dateformat) && dto.matches(dateformat))
               tracks(i) = (  src, 
                          if (dfrom == null) null else df.parse(dfrom),
                          if (dto == null || dto.equals("-/-") || dto.equals("-"))
                             new Date(); 
                          else
                             df.parse(dto) 
                       )
           else
              _dbp.log().warn("Webserver", "handle_gpx: Error in timestring format")
        }
        
           
        res.`type`("text/gpx+xml; charset=utf-8")
        res.header("Content-Disposition", "attachment; filename=\"tracks.gpx\"")
        
        val gpx = 
           <gpx xmlns="http://www.topografix.com/GPX/1/1" creator="Polaric Server" version="1.1" 
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
                xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
             <metadata>
                <name>APRS Tracks</name>
                <time>{xdf.format(new Date()) }</time>
             </metadata>
             {  try {
                    for (i <- 0 to ntracks-1) yield 
                        if (tracks(i) != null)
                            do_trail(tracks(i)._1, tracks(i)._2, tracks(i)._3)
                        else EMPTY
                }
                catch { case e: Exception => 
                    _dbp.log().warn("Db.Webserver", "GPX: "+e)
                } 
                finally { db.close() }
            }
           </gpx>
           ; 
        
        printXml(res, gpx)
   }
   
   
   
   
   def handle_rawAprsPackets(req : Request, res : Response) =
   {
       val df = new java.text.SimpleDateFormat("HH:mm:ss")
       val id = req.queryParams("ident")
       val db = _dbp.getDB(true) 
       val result: NodeSeq = 
       try {
          val list = db.getAprsPackets(id, 25)   
          
          <h1>Siste APRS pakker fra {id}</h1>
          <table>
          <tr><th>Kanal</th><th>Tid</th><th>Dest</th><th>Via</th><th>Innhold</th></tr>
          {
              for (it <- list.iterator) yield
                 <tr>
                     <td>{it.source.getIdent()}</td>
                     <td>{df.format(it.time)}</td>
                     <td>{it.to}</td>
                     <td>{it.via}</td>
                     <td>{it.report}</td>
                 </tr>      
          }
          </table>
       }
       catch { case e: java.sql.SQLException => 
          <h3>Det oppsto en feil</h3>
          <p>{e}</p>
       }
       finally { db.close() }
       
       printHtml(res, htmlBody(req, null, result))
   }
   
   

   
     
  }

}
