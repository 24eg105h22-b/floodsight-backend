package com.flooddepth.saferoute;

import com.flooddepth.saferoute.SaferRouteModels.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.flooddepth.saferoute.RoutePlanningException.Code.*;

@Service
public final class SaferRouteService {
    private final GeoapifyClient client;
    private final FloodRouteRiskService risk;
    public SaferRouteService(GeoapifyClient client,FloodRouteRiskService risk){this.client=client;this.risk=risk;}
    public Response plan(Request request){
        Point start;
        try{if(request==null||request.startLatitude()==null||request.startLongitude()==null)throw new IllegalArgumentException();start=new Point(request.startLatitude(),request.startLongitude());}
        catch(RuntimeException e){throw new RoutePlanningException(INVALID_START);}
        String text=request.destinationText();if(text==null||text.isBlank()||text.length()>200||text.chars().anyMatch(Character::isISOControl))throw new RoutePlanningException(INVALID_DESTINATION);
        var destination=client.geocode(text.strip(),start);var candidates=client.routes(start,destination);var assessment=risk.assess(candidates);
        var routes=assessment.routes();String selected=recommend(routes).id();var limitations=new ArrayList<String>();
        if(routes.stream().allMatch(r->r.floodEvidenceCount()==0))limitations.add("No significant flood evidence was found along the compared routes.");
        limitations.add("Limited flood reports may reduce coverage.");
        limitations.add("Historical observations indicate flood-prone areas, not current flooding. Recent observations may also be out of date.");
        limitations.add("Rainy-season advice compares available observations; live rainfall and road closures are not monitored.");
        limitations.add("Route proximity cannot prove which carriageway, bridge level or parallel road a report belongs to.");
        if(routes.size()<2)limitations.add("Only one distinct road route was available; alternatives could not be compared.");
        if(assessment.limitedCoverage())limitations.add("The report search reached its limit; flood coverage may be incomplete.");
        if(routes.stream().anyMatch(r->r.floodEvidenceCount()>100))limitations.add("Only the first 100 relevant observation markers per route are shown.");
        limitations.add("Never enter floodwater based on this recommendation. Follow local closures and conditions.");
        return new Response(start,destination,selected,routes,List.copyOf(limitations));
    }
    public static AssessedRoute recommend(List<AssessedRoute> routes){
        if(routes==null||routes.isEmpty())throw new RoutePlanningException(NO_ROUTES);
        double fastest=routes.stream().mapToDouble(AssessedRoute::durationSeconds).min().orElseThrow(),shortest=routes.stream().mapToDouble(AssessedRoute::distanceMeters).min().orElseThrow();
        var efficiency=Comparator.comparingDouble(AssessedRoute::durationSeconds).thenComparingDouble(AssessedRoute::distanceMeters).thenComparing(AssessedRoute::id);
        var baseline=routes.stream().min(efficiency).orElseThrow();
        return routes.stream().filter(r->r==baseline||(r.durationSeconds()<=2*fastest&&r.distanceMeters()<=2*shortest))
            .min(Comparator.comparingDouble((AssessedRoute r)->r.riskScore()+12*(r.durationSeconds()/fastest-1)+8*(r.distanceMeters()/shortest-1)).thenComparing(efficiency)).orElseThrow();
    }
}
