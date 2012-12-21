 
import java.util._
import java.io._
import uk.me.jstott.jcoord._
import scala.xml._
import scala.collection.jcl.Conversions._
import no.polaric.aprsd._
import no.polaric.aprsd.http.ServerUtils
import no.polaric.aprsd.http.ServerBase
import org.simpleframework.http.core.Container
import org.simpleframework.transport.connect.Connection
import org.simpleframework.transport.connect.SocketConnection
import org.simpleframework.http._




package no.polaric.aprsdb
{

  class Webserver 
      ( val api: ServerAPI ) extends ServerBase(api) with ServerUtils
  {
     
     
   val _dbp = api.properties().get("aprsdb.plugin").asInstanceOf[DatabasePlugin];

     
             
   /**
    * add or edit APRS object.
    */
   def handle_addSign(req : Request, res : Response) =
   {
        val pos = getUtmCoord(req, 'W', _utmzone)
        val id = req.getParameter("objid")
        val prefix = <h2>Legge inn enkelt objekt</h2>
        
        /* Fields to be filled in */
        def fields(req : Request): NodeSeq =
           <xml:group>
            <label for="scale" class="lleftlab">Max målestk:</label>
            <input id="scale" name="scale" type="text" size="9" maxlength="9"/>
            <br/>
            <label for="url" class="lleftlab">URL:</label>
            <input id="url" name="url" type="text" size="30" maxlength="40"/>
            <br/>
            <label for="descr" class="lleftlab">Beskrivelse:</label>
            <input id="descr" name="descr" type="text" size="30" maxlength="40"/>
            <br/>
            <label for="utmz" class="lleftlab">Pos (UTM): </label>
            {  if (pos==null)
                  utmForm('W', 34)
               else
                  showUTM(pos)
            }
            <br/>
            <br/>
            { iconSelect(null, fprefix(req)+"/icons/signs/") }
            
            </xml:group>
             
             
        /* Action. To be executed when user hits 'submit' button */
        def action(request : Request): NodeSeq =
           {
               val scale = Integer.parseInt(req.getParameter("scale"))
               val url = req.getParameter("url")
               val descr = req.getParameter("descr")
               val icon = req.getParameter("iconselect")
               val db = _dbp.getDB()
               try {
                  db.addSign(scale, "signs/"+icon, url, descr, pos.toLatLng());
                  db.commit();
               
                  <h2>Objekt registrert</h2>
                  <p>pos={showUTM(pos) }</p>;
               }
               catch { case e: java.sql.SQLException => 
                  <h2>SQL Feil</h2>
                  <p>{e}</p>
               }
               finally { db.close(); }
           };
            
        printHtml (res, htmlBody (req, null, htmlForm(req, prefix, fields, IF_AUTH(action))))
    }

     
     
     
     
  }

}