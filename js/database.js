
/* Add items to click-on-map menu */
ctxtMenu.addCallback("MAP", function (m)
{    
     if (!(isAdmin() || canUpdate()))
        return;
     m.add(null);
     m.add("Nytt enkelt objekt", function()
       { editSign(m.x, m.y); });
  });




/* Add items to sign menu */
ctxtMenu.addCallback("SIGN", function (m)
   {
     if (m.ident.substring(0,6) == '__sign' || !(isAdmin() || canUpdate()))
        return;
      m.add("Rediger enkelt objekt", function()
        { editSign(m.x, m.y, m.ident); });
      m.add("Slett enkelt objekt", function()
        { deleteSign(m.ident); });
   });




/* Add items to APRS item menu */
ctxtMenu.addCallback("ITEM", function (m)
   {
      var p = myOverlay.getPointObject(m.ident);
      if (p.flags == null || !p.flags.match("a"))
         return;
      m.add(null);
 
      m.add("Historikk...", function() 
         { setTimeout('searchHistData("'+m.ident+'");', 100); });  
      if (p != null && p.flags != null && p.flags.match("i")) {
        
          m.add("Hørte punkter via...", function() 
            { setTimeout('searchHeardPoints("'+m.ident+'");', 100); });
      }
      m.add("APRS pakker", function()
         { rawAprsPackets(m.ident); });
   });





/* Add items to main menu */
ctxtMenu.addCallback("MAIN", function (m)
   {
      m.add(null);
      if (isAdmin() || canUpdate()) {
         m.add("Nytt enkelt objekt", function()
           { editSign(null, null); });
         m.add("Mine trackere..", function()
           { listTrackers(); });
      }
      m.add("Historikk...", function() 
        { setTimeout('searchHistData(null);', 100); });
      m.add("Hørte punkter via...", function() 
      { setTimeout('searchHeardPoints(null);', 100); });
   });






function rawAprsPackets(ident)
{
   remotepopupwindow(document.getElementById("anchor"), server_url + 'srv/rawAprsPackets?ident='+ident, 50, 70, "aprspackets");
}




function searchPointsVia(call, days)
{
   var to = new Date(); 
   var from = new Date(); 
   from.setTime(from.getTime() - (24*60*60*1000) * days);
   getPointsXmlData(call, formatDate(from)+"/"+formatTime(from), formatDate(to)+"/"+formatTime(to));
}




function editSign(x, y, ident)
{
   var id = null;
   if (ident)
      id = ident.substring(2);
   var coord = myKaMap.pixToGeo(x, y);
   fullPopupWindow('Enkelt_objekt', server_url + 'srv/addSign' +
      (x==null ? "" : '?x=' + coord[0] + '&y='+ coord[1]) +
      (id == null? "" : '&edit=true&objid='+id),  570, (x==null ? 390 : 380));
}



function deleteSign(ident)
{
   var id = null;
   if (ident)
      id = ident.substring(2);
   fullPopupWindow('Slett_enkelt_objekt', server_url + 'srv/deleteSign' +
      (id == null? "" : '?objid='+id),  300, 200);
}

function listTrackers(ident)
{
  fullPopupWindow('Mine_trackere', server_url + 'srv/listTrackers',  300, 200);
}