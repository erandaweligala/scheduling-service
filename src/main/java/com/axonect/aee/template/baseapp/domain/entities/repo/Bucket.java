package com.axonect.aee.template.baseapp.domain.entities.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Entity
@Table(name = "BUCKET")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Bucket implements Serializable {

    @Id
    @Column(name = "BUCKET_ID", length = 64, nullable = false)
    private String bucketId;

    @Column(name = "BUCKET_NAME", length = 64, nullable = false)
    private String bucketName;

    @Column(name = "BUCKET_TYPE", length = 64, nullable = false)
    private String bucketType;

    @Column(name = "QOS_ID",nullable = false)
    private Long qosId;

    @Column(name = "PRIORITY",nullable = false)
    private Long priority;

    @Column(name = "TIME_WINDOW")
    private String timeWindow;
}
