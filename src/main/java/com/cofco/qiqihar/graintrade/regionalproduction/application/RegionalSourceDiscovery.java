package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Daily public-web discovery is separate from revisiting registered documents. */
@Component
public class RegionalSourceDiscovery {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RegionalSourceDiscovery.class);
    private final JdbcClient jdbc;
    private final String key;
    private final String searx;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Map<String,String> ROOTS = Map.of("230200","齐齐哈尔","231100","黑河","150700","呼伦贝尔","232700","大兴安岭");
    public RegionalSourceDiscovery(JdbcClient jdbc,
            @Value("${qiqihar.regional-public-data.search-key:${BRAVE_SEARCH_API_KEY:}}") String key,
            @Value("${qiqihar.regional-public-data.searx-url:${SEARXNG_URL:}}") String searx) {
        this.jdbc=jdbc; this.key=key; this.searx=searx;
    }

    public void markDailyDue(Instant now) {
        jdbc.sql("UPDATE production.regional_public_source SET next_refresh_at=:now WHERE active")
                .param("now",java.sql.Timestamp.from(now)).update();
    }

    public boolean dailyRefreshNeeded(Instant now) {
        var local=now.atZone(java.time.ZoneId.of("Asia/Shanghai"));
        var cutoff=local.toLocalDate().atTime(8,30).atZone(local.getZone()).toInstant();
        if (now.isBefore(cutoff)) return false;
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM production.regional_public_source WHERE active AND (last_attempt_at IS NULL OR last_attempt_at<:cutoff))")
                .param("cutoff",java.sql.Timestamp.from(cutoff)).query(Boolean.class).single();
    }

    public void discover(Instant now) {
        ROOTS.forEach((root,name) -> {
            if (Thread.currentThread().isInterrupted()) return;
            String id="search-"+root;
            Boolean due=jdbc.sql("SELECT next_refresh_at IS NULL OR next_refresh_at<=:now FROM production.regional_public_source WHERE source_id=:id")
                    .param("now",java.sql.Timestamp.from(now)).param("id",id).query(Boolean.class).optional().orElse(false);
            if (!due) return;
            if (key.isBlank() && searx.isBlank()) {
                status(id,now,"SEARCH_NOT_CONFIGURED","尚未配置后台搜索服务；固定来源核验仍运行，不能视为已完成全网检索",0);
                return;
            }
            int accepted=0, count=0, failedEngines=0;
            try {
                int year=now.atZone(java.time.ZoneId.of("Asia/Shanghai")).getYear();
                var queries = discoveryQueries(name,year);
                for (String phrase : queries) {
                    String query=URLEncoder.encode(phrase,StandardCharsets.UTF_8);
                    URI uri=URI.create(searx.isBlank() ? "https://api.search.brave.com/res/v1/web/search?q="+query+"&count=10"
                            : searx.replaceAll("/$","")+"/search?q="+query+"&format=json");
                    var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Accept","application/json");
                    if (searx.isBlank()) request.header("X-Subscription-Token",key);
                    var response=RegionalPublicHttp.send(http, request.GET().build(), 2_000_000);
                    if(response.statusCode()!=200) throw new IllegalStateException("搜索服务HTTP "+response.statusCode());
                    var json=JsonMapper.builder().build().readTree(response.body());
                    var results=searx.isBlank() ? json.path("web").path("results") : json.path("results");
                    if(!results.isArray()) throw new IllegalStateException("搜索服务返回格式异常");
                    failedEngines += json.path("unresponsive_engines").size();
                    for(var item:results) {
                        count++;
                        String title=item.path("title").asText("");
                        String url=item.path("url").asText("");
                        String description=item.path(searx.isBlank()?"description":"content").asText("");
                        if (title.matches(".*(?:笔试|模拟试题|题库|招聘|速记|考试答案).*")) continue;
                        if (!(title+description).contains(name) || !(title+description).matches(".*(?:农业|农牧|粮食|种植|蔬菜|畜牧|铁路|物流|统计公报|农作物).*")) continue;
                        if (!publicHttps(url)) continue;
                        String parser=isRootAnnualReport(title,name) ? switch(root) {
                            case "230200"->"ANNUAL_QQHR";case "231100"->"ANNUAL_HEIHE";
                            case "150700"->"ANNUAL_HLBE";default->"ANNUAL_DXAL";
                        } : "GENERIC_PAGE";
                        accepted+=jdbc.sql("""
                            INSERT INTO production.regional_public_source(source_id,root_region_code,source_type,
                              source_name,source_url,parser_key,evidence,last_status,source_class,reliability_weight)
                            SELECT 'search-found-'||md5(:root||:url),:root,:type,:title,:url,:parser,
                              '联网搜索发现；须以正文为依据，搜索摘要不直接作为统计数值。','WAITING_FOR_SOURCE_SYNC','PUBLIC_WEB',0.5
                            WHERE NOT EXISTS(SELECT 1 FROM production.regional_public_source WHERE root_region_code=:root AND source_url=:url)
                            ON CONFLICT(source_id) DO NOTHING
                            """).param("root",root).param("url",url).param("title",title.substring(0,Math.min(200,title.length())))
                                .param("parser",parser).param("type",title.matches(".*(?:政策|补贴|补助|通知|实施方案).* ".trim()) ? "POLICY" : "AGRICULTURE").update();
                    }
                }
                status(id,now,searchState(count,failedEngines),"联网检索"+queries.size()+"组关键词，返回"+count+"条结果；新增"+accepted+"个相关来源。"
                        + (failedEngines > 0 ? "有"+failedEngines+"次搜索引擎响应失败，未视为全部检索完成。" : "")
                        + "来源另行读取正文，搜索成功不等于全部数据已核验。",accepted);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch(Exception e) {
                LOG.warn("Regional source discovery failed [root={}]",root,e);
                status(id,now,"SEARCH_FAILED","联网搜索未完成，保留历史资料并稍后重试；本轮已登记"+accepted+"个候选来源。",accepted);
            }
        });
    }

    static boolean isRootAnnualReport(String title, String rootName) {
        // A county report mentioning its parent city must not become a city-wide observation.
        return title.replaceAll("\\s", "").matches(".*" + rootName + "(?:市|地区)?国民经济和社会发展统计公报.*");
    }

    static List<String> discoveryQueries(String name, int year) {
        var queries = new java.util.ArrayList<String>();
        // Reports published this year normally describe the preceding year; older reports train the model.
        for (int period=year-1; period>=year-3; period--)
            queries.add(name+" "+period+"年 国民经济和社会发展统计公报");
        for (String topic : List.of("种植 蔬菜 畜牧 产量", "农业 政策 气象 灾害", "粮食 调入 调出 铁路 货运 物流园", "农产品 冷链 仓储 加工", "乡镇 行政村 种植面积 产量", "农业 粮食 媒体 公众号"))
            queries.add(name+" "+year+" "+topic);
        return List.copyOf(queries);
    }

    static String searchState(int results, int failedEngines) {
        return failedEngines == 0 ? "SEARCH_SUCCESS" : results > 0 ? "SEARCH_PARTIAL" : "SEARCH_FAILED";
    }

    static boolean publicHttps(String value) {
        try {
            var uri=URI.create(value);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || (uri.getPort()!=-1 && uri.getPort()!=443)) return false;
            for(var ip:java.net.InetAddress.getAllByName(uri.getHost())) {
                if(ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
                byte[] bytes=ip.getAddress();
                if(bytes.length==16 && (bytes[0]&0xfe)==0xfc) return false;
            }
            return true;
        } catch(Exception ignored) { return false; }
    }

    private void status(String id,Instant now,String state,String explanation,int added) {
        var local=now.atZone(java.time.ZoneId.of("Asia/Shanghai"));
        Instant next=local.toLocalDate().plusDays(1).atTime(8,30).atZone(local.getZone()).toInstant();
        if(!state.equals("SEARCH_SUCCESS")) {
            Instant today=local.toLocalDate().atTime(8,30).atZone(local.getZone()).toInstant();
            if(today.isAfter(now)) next=today;
            if(now.plusSeconds(3600).isBefore(next)) next=now.plusSeconds(3600);
        }
        jdbc.sql("""
            UPDATE production.regional_public_source SET last_status=:state,evidence=:explanation,last_attempt_at=:now,
              last_success_at=CASE WHEN :state='SEARCH_SUCCESS' THEN :now ELSE last_success_at END,next_refresh_at=:next
            WHERE source_id=:id
            """).param("id",id).param("state",state).param("explanation",explanation)
                .param("now",java.sql.Timestamp.from(now)).param("next",java.sql.Timestamp.from(next)).update();
    }
}
