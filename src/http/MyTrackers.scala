 
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

  class MyTrackers 
      ( val api: ServerAPI ) extends ServerBase(api) with ServerUtils
  {
     
     
    val _dbp = api.properties().get("aprsdb.plugin").asInstanceOf[no.polaric.aprsdb.PluginApi];
    val sdf = new java.text.SimpleDateFormat("HH:mm")  
     
       
 
    def handle_listTrackers(req : Request, res : Response) =
    {
        val prefix = <h2>My trackers</h2>
        val db = _dbp.getDB(true)     
        val user = getAuthInfo(req).userid
        val addTracker = req.queryParams("addTracker")

        
        def fields(req : Request): NodeSeq = 
        {
           refreshPage(req, res, 60, "listTrackers")
           val user = getAuthInfo(req).userid
           val list = db.getTrackers(user)  
           if (list == null) _dbp.log().warn("Db.Webserver", "listTrackers: LIST IS NULL")
           <table>
           <tr><th></th><th>Ident</th><th>Alias</th><th>Ikon</th><th>Aktiv</th><th>Sist hørt</th><th>Beskr.</th></tr>
           {  
              if (!list.isEmpty)
                 for (it <- list.iterator; if it != null) yield {
                    val lastch = if (it.isActive()) it.getStation().getLastChanged() else null
                    val alias = if (it.isActive()) it.getStation().getAlias()
                                else it.info.alias
                    <tr>
                        <td><a href={"removeTracker?id="+it.info.id}>
                                <img title="remove" src="../images/edit-delete.png" height="14" id={"rmtracker_"+it.info.id} />
                            </a>&nbsp; 
                            <a href={"editTracker?edit=true&id="+it.info.id}>
                                <img title="edit" src="../images/edit.png" height="14" id={"editItem_"+it.info.id} />
                            </a>
                        </td>
                        <td>{ if (it.isActive() && it.getStation().visible()) 
                               <a href={"javascript:polaric.findItem('"+it.info.id+"', false);"}>{it.info.id}</a> 
                              else it.info.id } 
                        </td>
                        <td>{it.getAlias()}</td>
                        <td>{showIcon(req, it.getIcon(), "18")}</td>
                        <td>{if (it.isActive()) showIcon(req, "signs/ok.png", "18") else EMPTY}</td>
                        <td>{if (it.isActive() && lastch != null) sdf.format(lastch) else EMPTY}</td> 
                        <td>{if (it.isActive()) it.getStation().getDescr() else EMPTY}</td>
                    </tr>
                 }
           }
           </table> 
           <script type="text/javascript">{"init_polaric('_PARENT_', '*);"}</script>
           <script type="text/javascript" src="../Aprs/iframeApi.js"></script>
        }
               
               
        def action(req : Request): NodeSeq =
        {        
           val ident = req.queryParams("addTracker")
           try {
              db.addTracker(ident, user, null, null)
              _dbp.log().info("Db.Webserver", "Add tracker: '"+ident+"' by user '"+getAuthInfo(req).userid+"'")
              refreshPage(req, res, 2, "listTrackers")
              <h3>Tracker '{ident}' Added</h3>
           }
           catch { case e: java.sql.SQLException => 
              _dbp.log().warn("Db.Webserver", "listTrackers: '"+e)
              <h3>Couldn't update</h3>
              <p>{e}</p>
           }
        }
       
       
       
        def _submit(req: Request): NodeSeq = {
           { textInput("addTracker", 10, 30, addTracker) } ++
           <button type="submit" 
                   name="update" id="update">Add tracker</button>
           <button onclick="window.close(); return false" 
                   id="cancel"> Close </button>        
       }
       
       
       
       try {
          printHtml (res, htmlBody ( req, null, 
              htmlForm(req, prefix, IF_AUTH(fields), IF_AUTH(action),
                  false, _submit)))
       }
       catch { case e: Exception => 
          _dbp.log().warn("Db.Webserver", "listTrackers: '"+e)
          e.printStackTrace(System.out);
          val msg = 
            <h2>Error</h2>
            <p>{e}</p>
            ;
          printHtml(res, htmlBody(req, null, msg))
       }
       finally { db.close() }
    } 
    
    
    
    
    def handle_removeTracker(req:Request, res : Response) = {
        val id = req.queryParams("id")
        val user = getAuthInfo(req).userid
        val db = _dbp.getDB(true)
        
        val result: NodeSeq = try {
           db.deleteTracker(id) 
           _dbp.log().info("Db.Webserver", "Delete tracker: '"+id+"' by user '"+getAuthInfo(req).userid+"'")
           refreshPage(req, res, 2, "listTrackers")        
           <h3>Tracker fjernet fra lista</h3>
        }
        catch { case e: java.sql.SQLException => 
           <h3>Det oppsto en feil</h3>
           <p>{e}</p>
        }
        finally { db.close() }
        
        printHtml(res, htmlBody(req, null, result))
    }

    
      
      
      
   def handle_editTracker(req : Request, res : Response) =
   {
       val id = req.queryParams("id")
       var x:TrackerPoint = _api.getDB().getItem(id, null)
       var view:PointView = null
       
       if (x==null) {
          x = new Station(id)       
          try { 
             val t = _dbp.getDB(true).getTracker(id)
             x.setAlias(t.info.alias);
             x.setIcon(t.info.icon);
          }
          catch { case e: java.sql.SQLException => EMPTY }
          
          view = new TrackerView(api, x, true, req)
       }
       else
          view = PointView.getViewFor(x, api, true, req)
       
       
       def tracker_submit(req: Request): NodeSeq = {
           <button onclick="window.history.back(); return false" 
                   id="cancel"> Back </button>
           <button type="submit" 
                   name="update" id="update"> Update </button>
       }
       
       
       printHtml (res, htmlBody ( req, null, 
              htmlForm(req, null, view.fields, IF_AUTH((req: Request) => 
                {  refreshPage(req, res, 2, "listTrackers");
                   view.action(req);
                   _dbp.saveItem(x);
                   _dbp.log().info("Db.Webserver", "Edit tracker: '"+id+"' by user '"+getAuthInfo(req).userid+"'")
                    <h3>Updated</h3> 
                } ), false, tracker_submit)))
   }
   
     
  }

}
