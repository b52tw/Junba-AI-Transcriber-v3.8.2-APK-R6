package tw.junba.transcriber;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** R6 mobile port of the Windows offline KTV transcript review page. */
public final class KtvHtmlBuilder {
    private KtvHtmlBuilder() {}

    private static final Pattern LINE = Pattern.compile(
            "^\\s*\\[(?:約\\s*|~\\s*)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\s*[–-]\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?\\]\\s*(.*)$");

    private static final class Seg {
        long startMs;
        long endMs;
        String text;
        boolean estimated;
        Seg(long s, long e, String t, boolean estimated) { startMs=s; endMs=e; text=t; this.estimated=estimated; }
    }

    public static String build(String title, String transcript, String audioName) {
        List<Seg> segs = parse(transcript);
        JSONArray arr = new JSONArray();
        for (int i=0;i<segs.size();i++) {
            Seg s=segs.get(i);
            JSONObject o=new JSONObject();
            try {
                o.put("i", i); o.put("start", s.startMs/1000.0); o.put("end", s.endMs/1000.0);
                o.put("text", s.text); o.put("estimated", s.estimated);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        String payload = arr.toString().replace("</", "<\\/");
        String safeTitle = esc(title == null ? "逐字稿" : title);
        String safeAudio = esc(audioName == null ? "" : audioName);
        return "<!doctype html>\n<html lang=\"zh-Hant-TW\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>"+safeTitle+"｜KTV 錄音核對</title><style>" +
                ":root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#0b1220;color:#e5e7eb;font-family:system-ui,'Noto Sans TC','Microsoft JhengHei',sans-serif}" +
                "header{position:sticky;top:0;z-index:5;background:#0b1220ee;padding:14px;border-bottom:1px solid #334155}h1{font-size:19px;margin:0 0 10px}audio{width:100%}" +
                ".row{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-top:8px}button,input{font:inherit}button{padding:8px 11px;border:1px solid #475569;border-radius:8px;background:#1e293b;color:#fff}" +
                "main{padding:14px;max-width:1000px;margin:auto}.panel{border:1px solid #334155;border-radius:10px;background:#0f172a;padding:12px;margin-bottom:12px}" +
                ".phrase{display:inline;padding:3px 4px;border-radius:5px;line-height:2.05;cursor:pointer}.phrase.active{background:#075985;color:white}.time{color:#7dd3fc;font-variant-numeric:tabular-nums}" +
                ".seg{display:grid;grid-template-columns:90px 1fr;gap:10px;padding:10px 4px;border-bottom:1px solid #25354d}.seg.active{background:#123b5d}.small{font-size:12px;color:#94a3b8}.estimated{color:#fde68a}" +
                "</style></head><body><header><h1>"+safeTitle+"｜KTV 同步核對</h1>" +
                "<audio id=\"a\" controls preload=\"metadata\"></audio><div class=\"row\"><label>選擇原音檔：<input id=\"pick\" type=\"file\" accept=\"audio/*,video/*\"></label><button id=\"follow\">自動跟隨：開</button></div>" +
                "<div class=\"small\">原始檔名："+safeAudio+"。Android 瀏覽器基於檔案權限，第一次開啟時請選一次同一個原音檔。</div></header>" +
                "<main><section class=\"panel\"><b>完整全文同步</b><div id=\"full\"></div><div id=\"now\" class=\"time\">待播放</div></section>" +
                "<section class=\"panel\"><b>時間軸逐字稿</b><div id=\"list\"></div></section></main>" +
                "<script>const S="+payload+";const a=document.getElementById('a'),f=document.getElementById('full'),l=document.getElementById('list'),n=document.getElementById('now');let follow=true,active=-1;" +
                "const esc=s=>String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));" +
                "const t=x=>{x=Math.max(0,Math.floor(x||0));let h=Math.floor(x/3600),m=Math.floor(x%3600/60),s=x%60;return(h?String(h).padStart(2,'0')+':':'')+String(m).padStart(2,'0')+':'+String(s).padStart(2,'0')};" +
                "function render(){f.innerHTML='';l.innerHTML='';S.forEach((s,i)=>{let p=document.createElement('span');p.className='phrase';p.dataset.i=i;p.textContent=s.text+' ';p.title=t(s.start)+'–'+t(s.end);p.onclick=()=>{a.currentTime=s.start;a.play().catch(()=>{})};f.appendChild(p);let d=document.createElement('div');d.className='seg';d.dataset.i=i;d.innerHTML='<div class=\\\"time '+(s.estimated?'estimated':'')+'\\\">'+(s.estimated?'約 ':'')+t(s.start)+'</div><div>'+esc(s.text)+'</div>';d.onclick=()=>{a.currentTime=s.start;a.play().catch(()=>{})};l.appendChild(d)})}" +
                "function idx(now){for(let i=0;i<S.length;i++)if(now>=S[i].start&&now<(S[i].end||S[i].start+4))return i;return -1}" +
                "function tick(){let i=idx(a.currentTime||0);if(i<0||i===active)return;active=i;document.querySelectorAll('.active').forEach(x=>x.classList.remove('active'));let p=document.querySelector('.phrase[data-i=\\\"'+i+'\\\"]'),d=document.querySelector('.seg[data-i=\\\"'+i+'\\\"]');if(p)p.classList.add('active');if(d)d.classList.add('active');n.textContent=(S[i].estimated?'約 ':'')+t(S[i].start)+'｜'+S[i].text;if(follow&&p)p.scrollIntoView({block:'center',behavior:'smooth'})}" +
                "document.getElementById('pick').onchange=e=>{let x=e.target.files[0];if(x){a.src=URL.createObjectURL(x);a.load()}};document.getElementById('follow').onclick=e=>{follow=!follow;e.target.textContent='自動跟隨：'+(follow?'開':'關')};a.ontimeupdate=tick;a.onseeked=tick;render();</script></body></html>";
    }

    public static String toVtt(String transcript) {
        StringBuilder b = new StringBuilder("WEBVTT\n\n");
        for (Seg s : parse(transcript)) {
            b.append(vtt(s.startMs)).append(" --> ").append(vtt(s.endMs)).append('\n');
            if (s.estimated) b.append("[估算時間] ");
            b.append(s.text).append("\n\n");
        }
        return b.toString();
    }

    private static List<Seg> parse(String transcript) {
        List<Seg> out = new ArrayList<>();
        if (transcript == null) return out;
        String[] lines = transcript.replace("\r\n","\n").replace('\r','\n').split("\n");
        for (String line : lines) {
            String t=line.trim(); if (t.isEmpty()) continue;
            Matcher m=LINE.matcher(t); if (!m.matches()) continue;
            boolean est=t.startsWith("[約") || t.startsWith("[~");
            long start=parse(m.group(1),m.group(2),m.group(3));
            long end=m.group(4)==null?start+5000L:parse(m.group(4),m.group(5),m.group(6));
            if (end<=start) end=start+5000L;
            out.add(new Seg(start,end,m.group(7).trim(),est));
        }
        for (int i=0;i<out.size()-1;i++) if (out.get(i).endMs>out.get(i+1).startMs) out.get(i).endMs=out.get(i+1).startMs;
        return out;
    }

    private static long parse(String a,String b,String c) {
        long x=Long.parseLong(a), y=Long.parseLong(b), z=c==null?0:Long.parseLong(c);
        return (c==null ? x*60L+y : x*3600L+y*60L+z)*1000L;
    }
    private static String vtt(long ms) {
        long x=Math.max(0,ms),h=x/3600000L,m=(x%3600000L)/60000L,s=(x%60000L)/1000L,r=x%1000L;
        return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",h,m,s,r);
    }
    private static String esc(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
}
