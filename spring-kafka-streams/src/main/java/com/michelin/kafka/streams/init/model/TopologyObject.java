package com.michelin.kafka.streams.init.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TopologyObject {
    private TopologyObjectType type;
    private String objectName;
}
