 

/****** Experimental ******/

var hist_call = "";
var hist_fromdate = null;
var hist_fromtime= null;
var hist_todate = null;
var hist_totime = null;


var hist = [];



ctxtMenu.addCallback("ITEM", function (m)
   {
      var p = myOverlay.getPointObject(m.ident);
      m.add(null);
      m.add("Historikk...", function() 
        { setTimeout('searchHistData("'+m.ident+'");', 100); });
      
      if (p != null && p.flags != null && p.flags.match("i")) {
          m.add("Hørt siste uke...", function() 
            { setTimeout('searchPointsVia("'+m.ident+'", 7);', 100); });
          m.add("Hørt siste måned...", function() 
            { setTimeout('searchPointsVia("'+m.ident+'", 30);', 100); });
      }
   });


ctxtMenu.addCallback("TOOLBAR", function (m)
   {
      m.add(null);
      m.add("Historikk...", function() 
        { setTimeout('searchHistData(null);', 100); });
   });




function searchPointsVia(call, days)
{
   var to = new Date(); 
   var from = new Date(); 
   from.setTime(from.getTime() - (24*60*60*1000) * days);
   getPointsXmlData(call, formatDate(from)+"/"+formatTime(from), formatDate(to)+"/"+formatTime(to));
}


function getPointsXmlData(stn, tfrom, tto)
{ 
  abortCall(lastXmlCall);
  if (myOverlay != null) 
    myOverlay.removePoint(); 
  myOverlay.loadXml('srv/hpoints?'+extentQuery() + 
    '&station=' + stn + '&tfrom='+ tfrom + '&tto='+tto+ (clientses!=null? '&clientses='+clientses : ""));
}






/********************************************************************************************/


function searchHistData(call)
{
   if (hist_fromdate == null)
      hist_fromdate = formatDate(new Date());
   if (hist_todate == null || hist_todate == '-')
      hist_todate = formatDate(new Date());
   if (hist_fromtime == null)
      hist_fromtime = formatTime(new Date());
   if (hist_totime == null || hist_totime == '-')
      hist_totime = formatTime(new Date());   
   if (call != null)
      hist_call = call;

   
   var x = popupwindow(document.getElementById("anchor"),
        ' <h1>Generere historisk spor</h1><hr><form>' +
        ' <span class="sleftlab">Stasjon: </span><input type="text"  size="10" id="findcall" value="'
             + hist_call + '"/><br> '+
        ' <span class="sleftlab">Tid start: </span><input type="text"  size="10" id="tfrom" value="'
                 + hist_fromdate + 
              '"/>&nbsp;<input type="text"  size="4" id="tfromt" value="'
                 + hist_fromtime + '"/> <br> '+
        ' <span class="sleftlab">Tid slutt: </span><input type="text" size="10" id="tto" value="'
                 + hist_todate + 
              '"/>&nbsp;<input type="text" size="4" id="ttot" value="'
                + hist_totime + '"/>&nbsp; <input type="checkbox" id="ttoopen"> Åpen slutt <br> '+
        '<hr>'+
        '<div id="searchlist"></div>' +
        '<hr>'+
        ' <input id="searchbutton" type="button"' +
        ' value="Søk" />'+ 
        
        ' <input id="addbutton" type="button"' +
        ' value="Legg til" />'+
        
        ' <input id="showallbutton" type="button"' +
        ' value="Vis alle" />'+
        
        ' <input id="clearbutton" type="button"' +
        ' value="Nullstill" />'+
        
        '</form><br>', 50, 70, null);
        
        displayList(); 
        
        $('#ttoopen').click( function() {
          if ($('#ttoopen').attr('checked'))
              $('#tto,#ttot').prop('disabled',true);
          else 
              $('#tto,#ttot').removeProp('disabled');
        });
        
        $('#searchbutton').click( function() {
           getItem();
           getHistXmlData( hist_call, hist_fromdate+"/"+hist_fromtime, hist_todate+"/"+hist_totime ); 
        });
        
        $('#addbutton').click( function() {
	  getItem();
          hist.push({ call:hist_call, fromdate:hist_fromdate, todate:hist_todate, 
                      fromtime: hist_fromtime, totime: hist_totime});
          displayList(); 
        });
        
        $('#showallbutton').click( function() {
          showAll();
        });
        
        $('#clearbutton').click( function() {
          hist = [];
          displayList(); 
        });
        
        $('#tfrom,#tto').datepicker({ dateFormat: 'yy-mm-dd' });
        $(x).resizable(); 
}



function displayList()
{
   var txt = "";
   for (i=0; i<hist.length; i++)  
     txt += '<img title="remove" src="images/edit-delete.png" height="14" onclick="deleteItem('+i+');"> '+
            '<img title="edit" src="config/edit.png" height="14" onclick="editItem('+i+');"> '+
            hist[i].call + ' : ' + hist[i].fromdate+" "+ hist[i].fromtime + ' - ' +
            hist[i].todate + ' ' + hist[i].totime + '<br>'
   document.getElementById("searchlist").innerHTML = txt; 
}



Array.prototype.remove = function(from, to) {
  var rest = this.slice((to || from) + 1 || this.length);
  this.length = from < 0 ? this.length + from : from;
  return this.push.apply(this, rest);
};



function getItem()
{
    hist_call     = $('#findcall').val();
    hist_fromdate = $('#tfrom').val();
    hist_fromtime = $('#tfromt').val();
    if ($('#ttoopen').attr('checked')) 
       hist_todate = hist_totime = '-';
    else
    {
       hist_todate   = $('#tto').val();    
       hist_totime   = $('#ttot').val();
    }
}



function deleteItem(idx)
{
   hist.remove(idx);
   displayList();
}



function editItem(idx)
{
   $('#findcall').val(hist[idx].call);
   $('#tfrom').val(hist[idx].fromdate);
   $('#tfromt').val(hist[idx].fromtime);
   if (hist[idx].todate == '-') {
       $('#ttoopen').attr('checked', true);
       $('#tto,#ttot').prop('disabled',true);
       $('#tto').val(formatDate(new Date()));
       $('#ttot').val(formatTime(new Date()));
   }
   else {
       $('#ttoopen').attr('checked', false);
       $('#tto,#ttot').removeProp('disabled');
       $('#tto').val(hist[idx].todate);
       $('#ttot').val(hist[idx].totime);
   }
   getItem(); 
   deleteItem(idx);
}



function showAll()
{
  abortCall(lastXmlCall);
  if (myOverlay != null) 
    myOverlay.removePoint(); 
  
  for (i=0; i<hist.length; i++)  
    myOverlay.loadXml('srv/htrail?'+extentQuery() + '&scale='+currentScale+
      '&station=' + hist[i].call + '&tfrom='+ hist[i].fromdate+"/"+hist[i].fromtime + 
      '&tto='+  hist[i].todate+"/"+hist[i].totime + (clientses!=null? '&clientses='+clientses : ""));
}



function getHistXmlData(stn, tfrom, tto)
{ 
   abortCall(lastXmlCall);
   if (myOverlay != null) 
      myOverlay.removePoint(); 
   myOverlay.loadXml('srv/htrail?'+extentQuery() + '&scale='+currentScale+
     '&station=' + stn + '&tfrom='+ tfrom + '&tto='+tto+ (clientses!=null? '&clientses='+clientses : ""));
}



function formatDate(d)
{
    return ""+d.getFullYear() + "-" + 
       (d.getMonth()<9 ? "0" : "") + (d.getMonth()+1) + "-" +
       (d.getDate()<10 ? "0" : "")  + d.getDate();
}




function formatTime(d)
{
    return "" +
       (d.getHours()<10 ? "0" : "") + d.getHours() + ":" +
       (d.getMinutes()<10 ? "0" : "") + d.getMinutes();
}


