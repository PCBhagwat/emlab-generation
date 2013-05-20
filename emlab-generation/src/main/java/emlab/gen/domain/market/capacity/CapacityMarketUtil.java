/*******************************************************************************
 * Copyright 2013 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package emlab.gen.domain.market.capacity;

import org.springframework.data.neo4j.annotation.NodeEntity;
import org.springframework.transaction.annotation.Transactional;

import emlab.gen.domain.agent.EnergyProducer;
import emlab.gen.domain.technology.PowerPlant;

/**
 * @author Kaveri
 * 
 */

@NodeEntity
public class CapacityMarketUtil {

    private double operatingRevenue;
    private double runningHours;
    private PowerPlant plant;
    private EnergyProducer producer;
    private long tick;

    public double getOperatingRevenue() {
        return operatingRevenue;
    }

    public void setOperatingRevenue(double operatingRevenue) {
        this.operatingRevenue = operatingRevenue;
    }

    public double getRunningHours() {
        return runningHours;
    }

    public void setRunningHours(double runningHours) {
        this.runningHours = runningHours;
    }

    public PowerPlant getPlant() {
        return plant;
    }

    public void setPlant(PowerPlant plant) {
        this.plant = plant;
    }

    public EnergyProducer getProducer() {
        return producer;
    }

    public void setProducer(EnergyProducer producer) {
        this.producer = producer;
    }

    public long getTick() {
        return tick;
    }

    public void setTick(long tick) {
        this.tick = tick;
    }

    @Transactional
    public void specifyAndPersist(double opRevenue, double runningHours, long time, EnergyProducer energyProducer,
            PowerPlant plant) {
        specifyNotPersist(opRevenue, runningHours, time, energyProducer, plant);
        this.persist();
    }

    public void specifyNotPersist(double opRevenue, double runningHours, long time, EnergyProducer energyProducer,
            PowerPlant plant) {
        this.setOperatingRevenue(opRevenue);
        this.setRunningHours(runningHours);
        this.setPlant(plant);
        this.setProducer(energyProducer);
        this.setTick(time);
    }

}
