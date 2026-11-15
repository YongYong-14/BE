package io.github.yongyong14.parking_server.vehicle;

import jakarta.persistence.*;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

@Repository
@Lazy
public class VehicleRepository {
    @PersistenceContext private EntityManager em;
    Vehicle active(UUID userId) {
        return em.createQuery("select v from Vehicle v where v.userId = :userId and v.deletedAt is null", Vehicle.class)
                .setParameter("userId", userId).getResultStream().findFirst().orElse(null);
    }
    boolean shareUser(UUID userId) {
        return !em.createNativeQuery("SELECT id FROM public.users WHERE id = :id FOR SHARE", UUID.class)
                .setParameter("id", userId).getResultList().isEmpty();
    }
    void save(Vehicle vehicle) { em.persist(vehicle); }
    void flush() { em.flush(); }
}
