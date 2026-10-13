package com.flooddepth.saferoute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.saferoute.SaferRouteModels.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.util.*;
import static com.flooddepth.saferoute.RoutePlanningException.Code.*;

@Component
public final class GeoapifyClient {
    private final RestClient client;
    private final String key;
    private final ObjectMapper mapper=new ObjectMapper();
    @Autowired public GeoapifyClient(@Value("${geoapify.api-key:}") String key,@Value("${geoapify.timeout-ms:10000}") int timeout){
        var factory=requestFactory(timeout);
        this.client=RestClient.builder().requestFactory(factory).build();this.key=key;
    }
    static SimpleClientHttpRequestFactory requestFactory(int timeout){
        var factory=new SimpleClientHttpRequestFactory(){
            @Override protected void prepareConnection(java.net.HttpURLConnection connection,String method) throws java.io.IOException {
                super.prepareConnection(connection,method);connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Math.max(1000,Math.min(30000,timeout)));factory.setReadTimeout(Math.max(1000,Math.min(30000,timeout)));return factory;
    }
    GeoapifyClient(RestClient client,String key){this.client=client;this.key=key;}
    public Destination geocode(String text,Point start){
        var uri=uri("/v1/geocode/search").queryParam("text",text).queryParam("bias","proximity:"+start.longitude()+","+start.latitude())
            .queryParam("limit",1).queryParam("format","json");
        JsonNode results=get(uri).path("results");
        if(!results.isArray())throw new RoutePlanningException(ROUTING_UNAVAILABLE);
        if(results.isEmpty())throw new RoutePlanningException(DESTINATION_NOT_FOUND);
        JsonNode first=results.get(0);
        try {
            var p=point(first.path("lat"),first.path("lon"));String name=first.path("formatted").asText("").strip();
            if(name.isEmpty()||name.length()>400||name.contains(key)||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
            return new Destination(name,p.latitude(),p.longitude());
        }catch(RuntimeException e){throw new RoutePlanningException(ROUTING_UNAVAILABLE);}
    }
    public List<Candidate> routes(Point start,Destination destination){
        var unique=new LinkedHashMap<String,Candidate>();
        // Documented optimization types, never an invented alternatives query parameter.
        for(String type:List.of("balanced","short","less_maneuvers")){
            JsonNode data;
            try {data=get(uri("/v1/routing").queryParam("waypoints",start.latitude()+","+start.longitude()+"|"+destination.latitude()+","+destination.longitude())
                .queryParam("mode","drive").queryParam("type",type).queryParam("format","geojson").queryParam("units","metric"));}
            catch(RoutePlanningException e){if(unique.isEmpty())throw e;break;} // Keep actual valid routes if an optional variant fails.
            JsonNode features=data.path("features");if(!features.isArray())throw new RoutePlanningException(ROUTING_UNAVAILABLE);
            if(features.size()>10)throw new RoutePlanningException(ROUTING_UNAVAILABLE);
            for(JsonNode feature:features){
                try{
                    var geometry=parseGeometry(feature.path("geometry"));
                    if(RouteGeometry.distance(start,geometry.get(0))>250||RouteGeometry.distance(new Point(destination.latitude(),destination.longitude()),geometry.get(geometry.size()-1))>250)throw new IllegalArgumentException();
                    double distance=positive(feature.path("properties").path("distance")),duration=positive(feature.path("properties").path("time"));
                    if(distance>20_000_000||duration>7*86400)throw new IllegalArgumentException();
                    String encoded=RouteGeometry.encode(geometry);
                    boolean duplicate=unique.values().stream().anyMatch(existing->equivalent(existing.geometry(),geometry));
                    if(!duplicate && unique.size()<3)unique.put(encoded,new Candidate("route-"+(unique.size()+1),distance,duration,geometry,encoded));
                }catch(RuntimeException e){throw new RoutePlanningException(ROUTING_UNAVAILABLE);}
            }
            if(unique.size()>=3)break;
        }
        if(unique.isEmpty())throw new RoutePlanningException(NO_ROUTES);
        return List.copyOf(unique.values());
    }
    private static boolean equivalent(List<Point> a,List<Point> b){
        // Symmetric whole-path proximity keeps only genuinely different road paths (5 m tolerance).
        return a.stream().allMatch(p->RouteGeometry.routeDistance(p,b)<=5) && b.stream().allMatch(p->RouteGeometry.routeDistance(p,a)<=5);
    }
    static List<Point> parseGeometry(JsonNode geometry){
        String type=geometry.path("type").asText();JsonNode coordinates=geometry.path("coordinates");
        if(!coordinates.isArray()||!(type.equals("MultiLineString")||type.equals("LineString")))throw new IllegalArgumentException();
        Iterable<JsonNode> lines=type.equals("LineString")?List.of(coordinates):coordinates;var out=new ArrayList<Point>();
        for(JsonNode line:lines){if(!line.isArray()||line.size()<2)throw new IllegalArgumentException();
            for(int i=0;i<line.size();i++){
                JsonNode p=line.get(i);if(!p.isArray()||p.size()<2)throw new IllegalArgumentException();Point value=point(p.get(1),p.get(0));
                if(i==0&&!out.isEmpty()){if(RouteGeometry.distance(out.get(out.size()-1),value)>5)throw new IllegalArgumentException();if(value.equals(out.get(out.size()-1)))continue;}
                if(!out.isEmpty()&&RouteGeometry.distance(out.get(out.size()-1),value)>1_000_000)throw new IllegalArgumentException();
                out.add(value);if(out.size()>20000)throw new IllegalArgumentException();
            }
        }
        if(out.size()<2||out.stream().allMatch(p->p.equals(out.get(0))))throw new IllegalArgumentException();return List.copyOf(out);
    }
    private UriComponentsBuilder uri(String path){
        if(key==null||key.isBlank())throw new RoutePlanningException(ROUTING_UNAVAILABLE);
        return UriComponentsBuilder.fromUriString("https://api.geoapify.com").path(path).queryParam("apiKey",key);
    }
    private JsonNode get(UriComponentsBuilder builder){
        try{
            URI uri=builder.build().encode().toUri();
            String body=client.get().uri(uri).retrieve().body(String.class);
            if(body==null||body.length()>4_000_000)throw new IllegalArgumentException();
            JsonNode parsed=mapper.readTree(body);if(parsed==null||!parsed.isObject())throw new IllegalArgumentException();return parsed;
        }catch(Exception e){throw new RoutePlanningException(ROUTING_UNAVAILABLE);}
    }
    private static Point point(JsonNode lat,JsonNode lon){if(lat==null||lon==null||!lat.isNumber()||!lon.isNumber())throw new IllegalArgumentException();return new Point(lat.asDouble(),lon.asDouble());}
    private static double positive(JsonNode node){if(!node.isNumber()||!Double.isFinite(node.asDouble())||node.asDouble()<=0)throw new IllegalArgumentException();return node.asDouble();}
}
