package com.flooddepth.repository;

import com.flooddepth.entity.FloodReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface FloodReportRepository extends JpaRepository<FloodReport, Long> {

    @Query("""
            select r from FloodReport r
            where r.latitude between :minLat and :maxLat and r.longitude between :minLon and :maxLon
              and r.estimatedDepthCm >= 0 and r.riskLevel is not null
              and (r.depthConfidence in ('HIGH','MEDIUM','LOW') or r.depthConfidence is null)
              and r.observedAt <= :observedUntil
            order by r.observedAt desc, r.id asc
            """)
    List<FloodReport> findRouteArea(@Param("minLat") double minLat,@Param("maxLat") double maxLat,
            @Param("minLon") double minLon,@Param("maxLon") double maxLon,
            @Param("observedUntil") LocalDateTime observedUntil,org.springframework.data.domain.Pageable limit);

    @Query(value = """
            SELECT * FROM flood_reports
            WHERE latitude IS NOT NULL
              AND longitude IS NOT NULL
              AND ST_Distance_Sphere(
                    POINT(longitude, latitude),
                    POINT(:longitude, :latitude)
                  ) <= :radiusMeters
              AND (:observedFrom IS NULL OR observed_at >= :observedFrom)
              AND (:observedBefore IS NULL OR observed_at < :observedBefore)
            ORDER BY observed_at DESC
            """, nativeQuery = true)
    List<FloodReport> findNearby(
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("radiusMeters") double radiusMeters,
            @Param("observedFrom") LocalDateTime observedFrom,
            @Param("observedBefore") LocalDateTime observedBefore);
}
