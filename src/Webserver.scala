 
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
        val edit = ( "true".equals(req.getParameter("edit")))
        val prefix = if (edit) <h2>Redigere enkelt objekt</h2>
                     else <h2>Legge inn enkelt objekt</h2>
        
        /* Fields to be filled in */
        def fields(req : Request): NodeSeq =
            {
               val db = if (edit && id != null) _dbp.getDB()
                        else null;
               val obj = if (db != null) db.getSign(Integer.parseInt(id)) 
                        else null;
               if (id==null)
                   <h2>Feil: parameter 'objid' mangler i redigeringsforespørsel</h2>
               if (edit && obj == null) 
                   try { db.abort(); <h2>Finner ikke objekt med id={id}</h2>} 
                   finally { db.close(); }
               else  
                  if (db!=null) DBSession.putTrans(id, db)
                  <xml:group>
                  <label for="scale" class="lleftlab">Max målestk:</label>
                  <input id="scale" name="scale" type="text" size="9" maxlength="9" 
                     value={ if (edit) ""+obj.getScale() else "" } />
                  <br/>
                  <label for="url" class="lleftlab">URL:</label>
                  <input id="url" name="url" type="text" size="30" maxlength="40"
                     value={ if (edit) obj.getUrl() else "" } />
                  <br/>
                  <label for="descr" class="lleftlab">Beskrivelse:</label>
                  <input id="descr" name="descr" type="text" size="30" maxlength="40"
                      value={ if (edit) obj.getDescr() else "" } />   
                  <br/>
                  <label for="utmz" class="lleftlab">Pos (UTM): </label>
                  {  if (pos==null)
                        utmForm('W', 34)
                     else
                        showUTM(pos)
                  }
                  <br/>
                  <br/>
                  { iconSelect(obj, fprefix(req), "/icons/signs/") }
                  </xml:group>
           }  
             
             
             
        /* Action. To be executed when user hits 'submit' button */
        def action(request : Request): NodeSeq =
           {
               val scale = java.lang.Long.parseLong(req.getParameter("scale"))
               val url = req.getParameter("url")
               val descr = req.getParameter("descr")
               val icon = req.getParameter("iconselect")
               
               /* Try to get existing transaction and if not successful
                * or if not in edit mode, create a new one
                */
               var db = if (edit && id!= null) DBSession.getTrans(id).asInstanceOf[MyDBSession]
                        else null;
               if (db==null) db = _dbp.getDB()
               
               try {
                  if (edit && id !=null) { 
                     db.updateSign(Integer.parseInt(id), scale, "signs/"+icon, url, descr, pos.toLatLng())
                     db.commit()
                     <h2>Objekt {id} oppdatert</h2>
                  }
                  else {
                     db.addSign(scale, "signs/"+icon, url, descr, pos.toLatLng());
                     db.commit()
                     <h2>Objekt registrert</h2>
                     <p>pos={showUTM(pos) }</p>
                  }
               }
               catch { case e: java.sql.SQLException => 
                  db.abort()
                  <h2>SQL Feil</h2>
                  <p>{e}</p>
               }
               finally { db.close() }
           };
            
        printHtml (res, htmlBody (req, null, htmlForm(req, prefix, fields, IF_AUTH(action))))
    }

     
     
     
     
  }

}