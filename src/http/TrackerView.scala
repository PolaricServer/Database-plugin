 
/* 
 * Copyright (C) 2016 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

import java.util._
import java.io._
import scala.xml._
import uk.me.jstott.jcoord._
import no.polaric.aprsd._
import no.polaric.aprsdb._
import no.polaric.aprsd.http._
import org.xnap.commons.i18n._
import spark.Request;
import spark.Response;


   
package no.polaric.aprsdb.http
{

   class TrackerView 
      ( override val api: ServerAPI, override val model:TrackerPoint, override val canUpdate: Boolean, override val req: Request) 
            extends TrackerPointView(api, model, canUpdate, req) with ServerUtils
   {
       
            
       
       /** Basic settings. */
       /* FIXME: Add trail colour when ready. */
       override protected def basicSettings(req: Request) = 
           if (edit && canUpdate)
               <div>
               <br/>
               <label for="nalias" class="leftlab"> {I.tr("New alias")+":"} </label>
               { textInput("nalias", 10, 20, 
                    if (model.getAlias()==null) "" else model.getAlias()) }
               <br/>
               { iconSelect(req, model, fprefix(req), "/icons/") }
               </div>
            else EMPTY        
       
       
       
       /** Action for basic settings. */
       /* FIXME: Add trail colour when ready. */
       override protected def basicSettings_action(req: Request) = {
      
             /* Alias setting */
             var alias = fixText(req.queryParams("nalias"));
             var ch = false;
             if (alias != null && alias.length() > 0)      
                 ch = model.setAlias(alias);
             else
                { ch = model.setAlias(null)
                  alias = "NULL"
                }
             _api.log().info("TrackerPointView", 
                "ALIAS: '"+alias+"' for '"+model.getIdent()+"' by user '"+getAuthInfo(req).userid+"'")
             if (ch && api.getRemoteCtl() != null)
                 api.getRemoteCtl().sendRequestAll("ALIAS", model.getIdent()+" "+alias, null);

             /* Icon setting */
             var icon = req.queryParams("iconselect");
             if ("system".equals(icon)) 
                 icon = null; 
             if (model.setIcon(icon) && _api.getRemoteCtl() != null )
                 _api.getRemoteCtl().sendRequestAll("ICON", model.getIdent() + " " +
                    { if (icon==null) "NULL" else icon }, 
                    null);
            
             <h3>{I.tr("Updated")}</h3>
       }

       
       
       override def fields(req : Request): NodeSeq = 
           ident(req) ++
           basicSettings(req)
           ;
              
              
       override def action(req : Request): NodeSeq = 
           basicSettings_action(req)
           ;
   }
  
}
