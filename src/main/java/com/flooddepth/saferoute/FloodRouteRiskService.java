package com.flooddepth.saferoute;

import com.flooddepth.entity.FloodReport;
import com.flooddepth.repository.FloodReportRepository;
import com.flooddepth.saferoute.SaferRouteModels.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Rainy-condition observation scoring; no live weather or AI dependency, and no safety guarantee. */
@Service
public final class FloodRouteRiskService {
    private static final int MAX_REPORTS=10000;
    private final FloodReportRepository repository;
    private final double radius;
    private final ZoneId observationZone;
    private final Set<Integer> rainyMonths;
    private final Clock clock;
    @Autowired public FloodRouteRiskService(FloodReportRepository repository,
            @Value("${flood.routing.report-distance-meters:25}") double radius,
            @Value("${flood.routing.observation-zone:Asia/Kolkata}") String zone,
            @Value("${flood.routing.rainy-months:6,7,8,9,10}") String months){
        this(repository,radius,ZoneId.of(zone),parseMonths(months),Clock.systemUTC());
    }
    public FloodRouteRiskService(FloodReportRepository repository,double radius,ZoneId zone,Set<Integer> months,Clock clock){
        if(!Double.isFinite(radius)||radius<5||radius>50)throw new IllegalArgumentException("Road proximity must be 5–50 metres");
        this.repository=repository;this.radius=radius;this.observationZone=zone;this.rainyMonths=Set.copyOf(months);this.clock=clock;
    }
    private static Set<Integer> parseMonths(String text){var set=new HashSet<Integer>();for(String part:text.split(",")){int month=Integer.parseInt(part.strip());if(month<1||month>12)throw new IllegalArgumentException("Invalid rainy months");set.add(month);}return set;}
    public record Assessment(List<AssessedRoute> routes,boolean limitedCoverage){ }
    public Assessment assess(List<Candidate> candidates){
        Instant now=clock.instant();LocalDateTime until=LocalDateTime.ofInstant(now,observationZone);
        var reports=new LinkedHashMap<Object,FloodReport>();boolean limited=false;
        // At most two envelopes per path, with dateline splitting and bounded database selection.
        for(Candidate candidate:candidates)for(var box:RouteGeometry.bounds(candidate.geometry(),radius)){
            var rows=repository.findRouteArea(box.minLat(),box.maxLat(),box.minLon(),box.maxLon(),until,PageRequest.of(0,MAX_REPORTS+1));
            if(rows.size()>MAX_REPORTS)limited=true;
            for(FloodReport report:rows.stream().limit(MAX_REPORTS).toList())reports.putIfAbsent(report.getId()==null?report:report.getId(),report);
        }
        var routes=candidates.stream().map(c->assessRoute(c,reports.values(),now)).toList();return new Assessment(routes,limited);
    }
    private record Hit(FloodReport report,Point point,double proximity,double recency,boolean recent,double depthMin,double depthMax){ }
    private AssessedRoute assessRoute(Candidate route,Collection<FloodReport> reports,Instant now){
        var hits=new ArrayList<Hit>();
        for(FloodReport r:reports){
            if(!usable(r))continue;
            Instant observed=r.getObservedAt().atZone(observationZone).toInstant();if(observed.isAfter(now))continue;
            double min=r.getDepthMinCm()==null?r.getEstimatedDepthCm():r.getDepthMinCm(),max=r.getDepthMaxCm()==null?r.getEstimatedDepthCm():r.getDepthMaxCm();
            if(!Double.isFinite(min)||!Double.isFinite(max)||min<0||max<min||r.getEstimatedDepthCm()<min||r.getEstimatedDepthCm()>max)continue;
            Point p=new Point(r.getLatitude(),r.getLongitude());double distance=RouteGeometry.routeDistance(p,route.geometry());if(distance>radius)continue;
            long age=Duration.between(observed,now).toSeconds();boolean recent=age<=7*86400;
            hits.add(new Hit(r,p,1-.5*distance/radius,recency(observed,now),recent,min,max));
        }
        hits.sort(Comparator.comparing((Hit h)->h.report().getObservedAt()).reversed().thenComparingDouble(h->h.point().latitude()).thenComparingDouble(h->h.point().longitude()).thenComparing(h->h.report().getId(),Comparator.nullsLast(Long::compareTo)));
        var clusters=new ArrayList<List<Hit>>();
        for(Hit hit:hits){var cluster=clusters.stream().filter(c->RouteGeometry.distance(c.get(0).point(),hit.point())<=20).findFirst().orElse(null);if(cluster==null){cluster=new ArrayList<>();clusters.add(cluster);}cluster.add(hit);}
        double sum=0;
        for(var cluster:clusters){
            double strongest=cluster.stream().mapToDouble(h->contribution(h)).max().orElse(0);
            // Extra observations on distinct calendar days add bounded corroboration; repeated uploads do not.
            long days=cluster.stream().map(h->h.report().getObservedAt().toLocalDate()).distinct().count();
            sum+=strongest*(1+.1*Math.min(3,Math.max(0,days-1)));
        }
        double score=100*(1-Math.exp(-sum));score=Math.round(score*100)/100.0;
        int recent=(int)hits.stream().filter(Hit::recent).count(),historical=hits.size()-recent;
        var evidence=hits.stream().limit(100).map(h->new Evidence(h.point().latitude(),h.point().longitude(),h.report().getObservedAt().atZone(observationZone).toInstant(),h.depthMin(),h.depthMax(),
            h.report().getDepthConfidence()==null?"UNKNOWN":h.report().getDepthConfidence(),h.report().getRiskLevel().name(),h.recent()?"RECENT":"HISTORICAL")).toList();
        String summary=hits.isEmpty()?"No significant flood evidence was found along this route.":recent>0?
            "Recent flood reports occur near this route. Conditions may have changed.":"Historically higher flood risk: past observations occur near this route.";
        return new AssessedRoute(route.id(),route.distanceMeters(),route.durationSeconds(),risk(score),score,hits.size(),recent,historical,route.geometry(),route.encodedPolyline(),summary,evidence);
    }
    public double recency(Instant observed,Instant now){
        long age=Duration.between(observed,now).toSeconds();if(age<0)return 0;if(age<=6*3600)return 1;if(age<=86400)return .6;if(age<=7*86400)return .3;
        boolean seasonal=rainyMonths.contains(now.atZone(observationZone).getMonthValue())&&rainyMonths.contains(observed.atZone(observationZone).getMonthValue());
        double weight=seasonal?.18:.08;if(age>365L*86400)weight*=.5;return weight;
    }
    private static double contribution(Hit h){
        double severity=Math.max(Math.min(1,h.depthMax()/60),switch(h.report().getRiskLevel()){case LOW->.15;case MODERATE->.5;case HIGH->.8;case SEVERE->1;});
        double confidence=h.report().getDepthConfidence()==null?.4:switch(h.report().getDepthConfidence()){case "HIGH"->1;case "MEDIUM"->.75;default->.5;};
        return severity*confidence*h.proximity()*h.recency();
    }
    private static boolean usable(FloodReport r){
        return r!=null&&r.getLatitude()!=null&&r.getLongitude()!=null&&Double.isFinite(r.getLatitude())&&Double.isFinite(r.getLongitude())&&Math.abs(r.getLatitude())<=90&&Math.abs(r.getLongitude())<=180
            &&r.getEstimatedDepthCm()!=null&&Double.isFinite(r.getEstimatedDepthCm())&&r.getEstimatedDepthCm()>=0&&r.getRiskLevel()!=null&&r.getObservedAt()!=null
            &&(r.getDepthConfidence()==null||Set.of("LOW","MEDIUM","HIGH").contains(r.getDepthConfidence()));
    }
    public static Risk risk(double score){return score>=80?Risk.SEVERE:score>=50?Risk.HIGH:score>=20?Risk.MODERATE:Risk.LOW;}
}
