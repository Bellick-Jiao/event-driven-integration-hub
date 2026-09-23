package com.bellick.hub.loader.repository;

import com.bellick.hub.loader.model.DataWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataWarehouseRepository extends JpaRepository<DataWarehouse, String> {
}
