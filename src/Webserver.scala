 
import java.util._
import java.io._
import uk.me.jstott.jcoord._
import scala.xml._
import scala.collection.JavaConversions._
import no.polaric.aprsd._
import no.polaric.aprsd.http.ServerUtils
import no.polaric.aprsd.http.ServerBase
import no.polaric.aprsd.http.PointView
import no.polaric.aprsd.http.TrackerPointView
import spark.Request;
import spark.Response;



package no.polaric.aprsdb
{

  class Webserver 
      ( val api: ServerAPI ) extends ServerBase(api) with ServerUtils
  {
     
     
   val _dbp = api.properties().get("aprsdb.plugin").asInstanceOf[DatabasePlugin];
   val dateformat = "(\\-\\/\\-)|([0-9]{4}\\-[01][0-9]\\-[0-3][0-9]\\/[0-2][0-9]:[0-5][0-9])"
   val sdf = new java.text.SimpleDateFormat("HH:mm")  
     
     
     
            
   /* FIXME: See webconfig plugin. ConfigUtils.java */   
   protected def refreshPage(req: Request, resp: Response, t: Int, url: String) = 
   {
      val mid = req.queryParams("mid");
      val cid = req.queryParams("chan");
      var lang = req.queryParams("lang");
      lang = if (lang==null) "en" else lang
      val uparm = "?lang=" + lang + 
        { if (mid != null) "&mid="+mid else "" } + 
        { if (cid != null) "&chan="+cid else "" }
            
      resp.header("Refresh", t+";url=\""+url + uparm+"\"")
   }
        
     
     
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
             {
                _dbp.log().debug("Db.Webserver", "GPX file export")
                for (i <- 0 to ntracks-1) yield 
                    if (tracks(i) != null)
                       do_trail(tracks(i)._1, tracks(i)._2, tracks(i)._3)
                    else EMPTY
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
   
   
   
     
   def handle_deleteSign(req : Request, res : Response) =
   {
       val id = req.queryParams("objid")
       val prefix = <h2>Slett objekt</h2>
             
       def fields(req : Request): NodeSeq =
           <label for="objid" class="lleftlab">Objekt ID:</label>
           <input id="objid" name="objid" type="text" size="9" maxlength="9"
              value={if (id==null) "" else id.replaceFirst("@.*", "")} />;
      
      
       def action(req : Request): NodeSeq =
          if (id == null) {
              <h3>Feil:</h3>
              <p>må oppgi 'objid' som parameter</p>;
          }
          else {
              val db = _dbp.getDB(true)
              try {
                  db.deleteSign(Integer.parseInt(id)) 
                  _dbp.log().info("Db.Webserver", "Delete sign: '"+id+"' by user '"+getAuthUser(req)+"'")
                  <h3>Objekt slettet!</h3>
              }
              catch { case e: java.sql.SQLException => 
                  <h3>Kunne ikke slette</h3>
                  <p>{e}</p>
              }
              finally { db.close() }
          }  
          
       printHtml (res, htmlBody (req, null, htmlForm(req, prefix, fields, IF_AUTH(action) )))
   }
   
   
             
   /**
    * add or edit APRS object.
    */
   def handle_addSign(req : Request, res : Response) =
   {
   
        val pos = getCoord(req)
        val id = req.queryParams("objid")
        val edit = ( "true".equals(req.queryParams("edit")))
        val prefix = if (edit) <h2>Redigere enkelt objekt</h2>
                     else <h2>Legge inn enkelt objekt</h2>
        
        /* Fields to be filled in */
        def fields(req : Request): NodeSeq =
            {
               val db = _dbp.getDB()
               val obj = if (edit && db != null) db.getSign(Integer.parseInt(id)) 
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
                  <input id="url" name="url" type="text" size="30" maxlength="60"
                     value={ if (edit) obj.getUrl() else "" } />
                  <br/>
                  
                  <label for="descr" class="lleftlab">Beskrivelse:</label>
                  <input id="descr" name="descr" type="text" size="30" maxlength="60"
                      value={ if (edit) obj.getDescr() else "" } />   
                  <br/>
                  
                  <label for="cls" class="lleftlab">Kategori:</label>
                  { classList(db, "cls", obj) }
                  <br/>
                  
                  <label for="utmz" class="lleftlab">Pos (UTM): </label>
                  {  if (pos==null)
                        utmForm('W', 34)
                     else
                        showUTM(req, pos)
                  }
                  <br/>
                  <br/>
                  { iconSelect(req, obj, fprefix(req), "/icons/signs/") }
                  </xml:group>
           }  
             
             
             
        def classList(db: MyDBSession, id: String, obj: Sign) = 
             <select id={id} name={id} class="symChoice">
             {
                 for (opt <- db.getCategories().iterator) yield {
                    <option value={"\""+opt.id+"\""} style={"background-image:url(../aprsd/icons/"+ opt.icon + ")"}
                      selected={if (obj != null && obj.getCategory() == opt.id) "selected" else null} >{opt.name}
                    </option>
                 }
             }
             </select>
             ;
             
             
             
        /* Action. To be executed when user hits 'submit' button */
        def action(request : Request): NodeSeq =
           {
               val tscale = req.queryParams("scale")
               val scale = if (tscale==null) 1000000 else java.lang.Long.parseLong(tscale)
               val url = req.queryParams("url")
               val cls = req.queryParams("cls")
               val descr = req.queryParams("descr")
               var icon = req.queryParams("iconselect")
               
               /* Try to get existing transaction and if not successful
                * or if not in edit mode, create a new one
                */
               var db = if (edit && id!= null) DBSession.getTrans(id).asInstanceOf[MyDBSession]
                        else null;
               if (db==null) db = _dbp.getDB()
               
               val cls_n = if (cls==null) 0 
                           else Integer.parseInt(cls.substring(1,cls.length-1))
                           
               icon = if (icon == null || icon.equals("system")) null 
                      else "signs/"+icon
               
               try {
                  if (edit && id !=null) { 
                     db.updateSign(Integer.parseInt(id), scale, icon, url, descr, pos.toLatLng(), cls_n)
                     db.commit()
                     <h2>Objekt {id} oppdatert</h2>
                  }
                  else {
                     db.addSign(scale, icon, url, descr, pos.toLatLng(), cls_n);
                     db.commit()
                     <h2>Objekt registrert</h2>
                     <p>pos={showUTM(req, pos) }</p>
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

     
     
    def handle_listTrackers(req : Request, res : Response) =
    {
        val prefix = <h2>Mine trackere</h2>
        val db = _dbp.getDB(true)     
        val user = getAuthUser(req)
        val addTracker = req.queryParams("addTracker")
        refreshPage(req, res, 60, "listTrackers")
        
                  
        def fields(req : Request): NodeSeq = 
        {
           val user = getAuthUser(req)
           val list = db.getTrackers(user)  
           if (list == null) _dbp.log().warn("Db.Webserver", "listTrackers: LIST IS NULL '")
           <table>
           <tr><th></th><th>Ident</th><th>Alias</th><th>Ikon</th><th>Aktiv</th><th>Sist hørt</th><th>Beskr.</th></tr>
           {  
              if (!list.isEmpty)
                 for (it <- list.iterator; if it != null) yield {
                    val lastch = if (it.isActive()) it.getStation().getLastChanged() else null
                    val alias = if (it.isActive()) it.getStation().getAlias()
                                else it.alias
                    <tr>
                        <td><a href={"removeTracker?id="+it.id}>
                                <img title="remove" src="../images/edit-delete.png" height="14" id={"rmtracker_"+it.id} />
                            </a>&nbsp; 
                            <a href={"editTracker?edit=true&id="+it.id}>
                                <img title="edit" src="../images/edit.png" height="14" id={"editItem_"+it.id} />
                            </a>
                        </td>
                        <td>{ if (it.isActive() && it.getStation().visible()) 
                               <a href={"javascript:polaric.findItem('"+it.id+"', false);"}>{it.id}</a> 
                              else it.id } 
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
              _dbp.log().info("Db.Webserver", "Add tracker: '"+ident+"' by user '"+getAuthUser(req)+"'")
              <h3>Tracker '{ident}' lagt til</h3>
           }
           catch { case e: java.sql.SQLException => 
              _dbp.log().warn("Db.Webserver", "listTrackers: '"+e)
              <h3>Kunne ikke oppdatere</h3>
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
                  false, -1, _submit)))
       }
       catch { case e: java.sql.SQLException => 
          _dbp.log().warn("Db.Webserver", "listTrackers: '"+e)
          val msg = 
            <h2>Det oppsto en feil</h2>
            <p>{e}</p>
            ;
          printHtml(res, htmlBody(req, null, msg))
       }
       finally { db.close() }
    } 
    
    
    
    
    def handle_removeTracker(req:Request, res : Response) = {
        val id = req.queryParams("id")
        val user = getAuthUser(req)
        val db = _dbp.getDB(true)
        
        val result: NodeSeq = try {
           db.deleteTracker(id) 
           _dbp.log().info("Db.Webserver", "Delete tracker: '"+id+"' by user '"+getAuthUser(req)+"'")
            <script source="javascript">setTimeout('window.history.go(-1)', 1000);</script>
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
             x.setAlias(t.alias);
             x.setIcon(t.icon);
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
                      { view.action(req);
                        _dbp.saveItem(x);
                        _dbp.log().info("Db.Webserver", "Edit tracker: '"+id+"' by user '"+getAuthUser(req)+"'")
                        <h3>Updated</h3> } ),
                  false, tracker_submit)))
   }
   
     
  }

}
