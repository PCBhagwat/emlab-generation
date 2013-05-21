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
package emlab.gen.role.investment;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.data.annotation.Transient;
import org.springframework.data.neo4j.annotation.NodeEntity;
import org.springframework.data.neo4j.aspects.core.NodeBacked;
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.transaction.annotation.Transactional;

import agentspring.role.Role;
import emlab.gen.domain.agent.BigBank;
import emlab.gen.domain.agent.EnergyProducer;
import emlab.gen.domain.agent.PowerPlantManufacturer;
import emlab.gen.domain.agent.Regulator;
import emlab.gen.domain.agent.StrategicReserveOperator;
import emlab.gen.domain.contract.CashFlow;
import emlab.gen.domain.contract.Loan;
import emlab.gen.domain.gis.Zone;
import emlab.gen.domain.market.ClearingPoint;
import emlab.gen.domain.market.electricity.ElectricitySpotMarket;
import emlab.gen.domain.market.electricity.Segment;
import emlab.gen.domain.market.electricity.SegmentLoad;
import emlab.gen.domain.policy.PowerGeneratingTechnologyTarget;
import emlab.gen.domain.technology.PowerGeneratingTechnology;
import emlab.gen.domain.technology.PowerGeneratingTechnologyNodeLimit;
import emlab.gen.domain.technology.PowerGridNode;
import emlab.gen.domain.technology.PowerPlant;
import emlab.gen.domain.technology.Substance;
import emlab.gen.domain.technology.SubstanceShareInFuelMix;
import emlab.gen.repository.Reps;
import emlab.gen.repository.StrategicReserveOperatorRepository;
import emlab.gen.util.GeometricTrendRegression;
import emlab.gen.util.MapValueComparator;

/**
 * {@link EnergyProducer}s decide to invest in new {@link PowerPlant}
 * 
 * @author <a href="mailto:E.J.L.Chappin@tudelft.nl">Emile Chappin</a> @author
 *         <a href="mailto:A.Chmieliauskas@tudelft.nl">Alfredas
 *         Chmieliauskas</a>
 * @author JCRichstein
 */
@Configurable
@NodeEntity
public class InvestmentWithCapacityMarket2<T extends EnergyProducer> extends GenericInvestmentRole<T> implements
Role<T>, NodeBacked {

    @Transient
    @Autowired
    Reps reps;

    @Transient
    @Autowired
    Neo4jTemplate template;

    @Transient
    @Autowired
    StrategicReserveOperatorRepository strategicReserveOperatorRepository;

    // market expectations
    @Transient
    Map<ElectricitySpotMarket, MarketInformation> marketInfoMap = new HashMap<ElectricitySpotMarket, MarketInformation>();


    @Override
    public void act(T agent) {

        long futureTimePoint = getCurrentTick() + agent.getInvestmentFutureTimeHorizon();
        // logger.warn(agent + " is looking at timepoint " + futureTimePoint);

        // ==== Expectations ===

        Map<Substance, Double> expectedFuelPrices = predictFuelPrices(agent, futureTimePoint);

        // CO2
        Map<ElectricitySpotMarket, Double> expectedCO2Price = determineExpectedCO2PriceInclTax(futureTimePoint,
                agent.getNumberOfYearsBacklookingForForecasting());

        // logger.warn(expectedCO2Price.toString());

        // Demand
        Map<ElectricitySpotMarket, Double> expectedDemand = new HashMap<ElectricitySpotMarket, Double>();
        for (ElectricitySpotMarket elm : reps.template.findAll(ElectricitySpotMarket.class)) {
            GeometricTrendRegression gtr = new GeometricTrendRegression();
            for (long time = getCurrentTick(); time > getCurrentTick()
                    - agent.getNumberOfYearsBacklookingForForecasting()
                    && time >= 0; time = time - 1) {
                gtr.addData(time, elm.getDemandGrowthTrend().getValue(time));
            }
            expectedDemand.put(elm, gtr.predict(futureTimePoint));
        }

        Map<PowerPlant, Double> expectedESMOperatingRevenueMap = new HashMap<PowerPlant, Double>();

        Map<PowerPlant, Double> runningHoursMap = new HashMap<PowerPlant, Double>();

        // Investment decision
        // for (ElectricitySpotMarket market :
        // reps.genericRepository.findAllAtRandom(ElectricitySpotMarket.class))
        // {
        ElectricitySpotMarket market = agent.getInvestorMarket();
        MarketInformation marketInformation = new MarketInformation(market, expectedDemand, expectedFuelPrices,
                expectedCO2Price.get(market).doubleValue(), futureTimePoint);

        for (PowerGeneratingTechnology technology : reps.genericRepository.findAll(PowerGeneratingTechnology.class)) {

            logger.warn("Hello! I am inside the FIRST power generation technology for loop . ");

            PowerPlant plant = new PowerPlant();
            plant.specifyNotPersist(getCurrentTick(), agent, getNodeForZone(market.getZone()), technology);

            // CALCULATE EXPECTED NET REVENUE FROM ENERGY MARKET for every
            // power plant

            Map<Substance, Double> myFuelPrices = new HashMap<Substance, Double>();
            for (Substance fuel : technology.getFuels()) {
                myFuelPrices.put(fuel, expectedFuelPrices.get(fuel));
            }
            Set<SubstanceShareInFuelMix> fuelMix = calculateFuelMix(plant, myFuelPrices, expectedCO2Price.get(market));
            plant.setFuelMix(fuelMix);

            double expectedMarginalCost = determineExpectedMarginalCost(plant, expectedFuelPrices,
                    expectedCO2Price.get(market));
            double expectedGrossProfit = 0d;
            double runningHours = 0d;

            long numberOfSegments = reps.segmentRepository.count();

            for (SegmentLoad segmentLoad : market.getLoadDurationCurve()) {
                double expectedElectricityPrice = marketInformation.expectedElectricityPricesPerSegment.get(segmentLoad
                        .getSegment());
                double hours = segmentLoad.getSegment().getLengthInHours();
                if (expectedMarginalCost <= expectedElectricityPrice) {
                    runningHours += hours;
                    expectedGrossProfit += (expectedElectricityPrice - expectedMarginalCost) * hours
                            * plant.getAvailableCapacity(futureTimePoint, segmentLoad.getSegment(), numberOfSegments);
                }
            }

            double fixedOMCost = calculateFixedOperatingCost(plant);
            double operatingProfitWithoutCapacityRevenue = expectedGrossProfit - fixedOMCost;
            putOperatingProfitESM(plant, operatingProfitWithoutCapacityRevenue);
            putRunningHours(plant, runningHours);

            // logger.warn("Hash map key " + plant.getName());
            // logger.warn("Hash map get value for key " +
            // expectedESMOperatingRevenueMap.get(plant));
            logger.warn("Hash map get value for running hours" + runningHoursMap.get(plant));

            // END CALCULATION OF NET REVENUE FROM esm

        }
        /*
         * if (marketInfoMap.containsKey(market) &&
         * marketInfoMap.get(market).time == futureTimePoint) {
         * marketInformation = marketInfoMap.get(market); } else {
         * marketInformation = new MarketInformation(market, expectedFuelPrices,
         * expectedCO2Price, futureTimePoint); marketInfoMap.put(market,
         * marketInformation); }
         */

        // logger.warn(agent + " is expecting a CO2 price of " +
        // expectedCO2Price.get(market) + " Euro/MWh at timepoint "
        // + futureTimePoint + " in Market " + market);

        // logger.warn("Agent {}  found the expected prices to be {}", agent,
        // marketInformation.expectedElectricityPricesPerSegment);

        // logger.warn("Agent {}  found that the installed capacity in the market {} in future to be "
        // + marketInformation.capacitySum +
        // "and expectde maximum demand to be "
        // + marketInformation.maxExpectedLoad, agent, market);

        double highestValue = Double.MIN_VALUE;
        PowerGeneratingTechnology bestTechnology = null;

        for (PowerGeneratingTechnology technology : reps.genericRepository.findAll(PowerGeneratingTechnology.class)) {

            PowerPlant plant = new PowerPlant();
            plant.specifyNotPersist(getCurrentTick(), agent, getNodeForZone(market.getZone()), technology);
            // if too much capacity of this technology in the pipeline (not
            // limited to the 5 years)
            double expectedInstalledCapacityOfTechnology = reps.powerPlantRepository
                    .calculateCapacityOfExpectedOperationalPowerPlantsInMarketAndTechnology(market, technology,
                            futureTimePoint);
            PowerGeneratingTechnologyTarget technologyTarget = reps.powerGenerationTechnologyTargetRepository
                    .findOneByTechnologyAndMarket(technology, market);
            if (technologyTarget != null) {
                double technologyTargetCapacity = technologyTarget.getTrend().getValue(futureTimePoint);
                expectedInstalledCapacityOfTechnology = (technologyTargetCapacity > expectedInstalledCapacityOfTechnology) ? technologyTargetCapacity
                        : expectedInstalledCapacityOfTechnology;
            }
            double pgtNodeLimit = Double.MAX_VALUE;
            PowerGeneratingTechnologyNodeLimit pgtLimit = reps.powerGeneratingTechnologyNodeLimitRepository
                    .findOneByTechnologyAndNode(technology, plant.getLocation());
            if (pgtLimit != null) {
                pgtNodeLimit = pgtLimit.getUpperCapacityLimit(futureTimePoint);
            }
            double expectedInstalledCapacityOfTechnologyInNode = reps.powerPlantRepository
                    .calculateCapacityOfExpectedOperationalPowerPlantsByNodeAndTechnology(plant.getLocation(),
                            technology, futureTimePoint);
            double expectedOwnedTotalCapacityInMarket = reps.powerPlantRepository
                    .calculateCapacityOfExpectedOperationalPowerPlantsInMarketByOwner(market, futureTimePoint, agent);
            double expectedOwnedCapacityInMarketOfThisTechnology = reps.powerPlantRepository
                    .calculateCapacityOfExpectedOperationalPowerPlantsInMarketByOwnerAndTechnology(market, technology,
                            futureTimePoint, agent);
            double capacityOfTechnologyInPipeline = reps.powerPlantRepository
                    .calculateCapacityOfPowerPlantsByTechnologyInPipeline(technology, getCurrentTick());
            double operationalCapacityOfTechnology = reps.powerPlantRepository
                    .calculateCapacityOfOperationalPowerPlantsByTechnology(technology, getCurrentTick());
            double capacityInPipelineInMarket = reps.powerPlantRepository
                    .calculateCapacityOfPowerPlantsByMarketInPipeline(market, getCurrentTick());

            if ((expectedInstalledCapacityOfTechnology + plant.getActualNominalCapacity())
                    / (marketInformation.maxExpectedLoad + plant.getActualNominalCapacity()) > technology
                    .getMaximumInstalledCapacityFractionInCountry()) {
                // logger.warn(agent +
                // " will not invest in {} technology because there's too much of this type in the market",
                // technology);
            } else if ((expectedInstalledCapacityOfTechnologyInNode + plant.getActualNominalCapacity()) > pgtNodeLimit) {

            } else if (expectedOwnedCapacityInMarketOfThisTechnology > expectedOwnedTotalCapacityInMarket
                    * technology.getMaximumInstalledCapacityFractionPerAgent()) {
                // logger.warn(agent +
                // " will not invest in {} technology because there's too much capacity planned by him",
                // technology);
            } else if (capacityInPipelineInMarket > 0.2 * marketInformation.maxExpectedLoad) {
                // logger.warn("Not investing because more than 20% of demand in pipeline.");

            } else if ((capacityOfTechnologyInPipeline > 2.0 * operationalCapacityOfTechnology)
                    && capacityOfTechnologyInPipeline > 9000) { // TODO:
                // reflects that you cannot expand a technology out of zero.
                // logger.warn(agent +
                // " will not invest in {} technology because there's too much capacity in the pipeline",
                // technology);
            } else if (plant.getActualInvestedCapital() * (1 - agent.getDebtRatioOfInvestments()) > agent
                    .getDownpaymentFractionOfCash() * agent.getCash()) {
                // logger.warn(agent +
                // " will not invest in {} technology as he does not have enough money for downpayment",
                // technology);
            } else {

                // logger.warn(agent +
                // "expects technology {} to have {} running", technology,
                // runningHours);
                // expect to meet minimum running hours?
                if (getRunningHours(plant) < plant.getTechnology().getMinimumRunningHours()) {
                    // logger.warn(agent+
                    // " will not invest in {} technology as he expect to have {} running, which is lower then required",
                    // technology, runningHours);
                } else {

                    Zone zoneTemp = market.getZone();
                    Regulator regulator = reps.regulatorRepository.findRegulatorForZone(zoneTemp);

                    double capacityRevenue = 0d;
                    if ((agent.isSimpleCapacityMarketEnabled()) && (regulator != null)) {

                        CapacityMarketInformation capacityMarketInformation = new CapacityMarketInformation(market,
                                expectedDemand, futureTimePoint);

                        capacityRevenue = capacityMarketInformation.expectedCapacityMarketPrice;
                    } else {
                        capacityRevenue = 0;
                    }

                    double operatingProfit = expectedESMOperatingRevenueMap.get(plant) + capacityRevenue;

                    double wacc = (1 - agent.getDebtRatioOfInvestments()) * agent.getEquityInterestRate()
                            + agent.getDebtRatioOfInvestments() * agent.getLoanInterestRate();

                    // Creation of out cash-flow during power plant building
                    // phase (note that the cash-flow is negative!)
                    TreeMap<Integer, Double> discountedProjectCapitalOutflow = calculateSimplePowerPlantInvestmentCashFlow(
                            technology.getDepreciationTime(), (int) plant.getActualLeadtime(),
                            plant.getActualInvestedCapital(), 0);
                    // Creation of in cashflow during operation
                    TreeMap<Integer, Double> discountedProjectCashInflow = calculateSimplePowerPlantInvestmentCashFlow(
                            technology.getDepreciationTime(), (int) plant.getActualLeadtime(), 0, operatingProfit);

                    double discountedCapitalCosts = npv(discountedProjectCapitalOutflow, wacc);// are
                    // defined
                    // negative!!
                    // plant.getActualNominalCapacity();

                    // logger.warn("Agent {}  found that the discounted capital for technology {} to be "
                    // + discountedCapitalCosts, agent,
                    // technology);

                    double discountedOpProfit = npv(discountedProjectCashInflow, wacc);

                    // logger.warn("Agent {}  found that the projected discounted inflows for technology {} to be "
                    // + discountedOpProfit,
                    // agent, technology);

                    double projectValue = discountedOpProfit + discountedCapitalCosts;

                    // logger.warn(
                    // "Agent {}  found the project value for technology {} to be "
                    // + Math.round(projectValue /
                    // plant.getActualNominalCapacity()) +
                    // " EUR/kW (running hours: "
                    // + runningHours + "", agent, technology);

                    // double projectTotalValue = projectValuePerMW *
                    // plant.getActualNominalCapacity();

                    // double projectReturnOnInvestment = discountedOpProfit
                    // / (-discountedCapitalCosts);

                    /*
                     * Divide by capacity, in order not to favour large power
                     * plants (which have the single largest NPV
                     */

                    if (projectValue > 0 && projectValue / plant.getActualNominalCapacity() > highestValue) {
                        highestValue = projectValue / plant.getActualNominalCapacity();
                        bestTechnology = plant.getTechnology();
                    }
                }

            }
        }

        if (bestTechnology != null) {
            // logger.warn("Agent {} invested in technology {} at tick " +
            // getCurrentTick(), agent, bestTechnology);

            PowerPlant plant = new PowerPlant();
            plant.specifyAndPersist(getCurrentTick(), agent, getNodeForZone(market.getZone()), bestTechnology);
            PowerPlantManufacturer manufacturer = reps.genericRepository.findFirst(PowerPlantManufacturer.class);
            BigBank bigbank = reps.genericRepository.findFirst(BigBank.class);

            double investmentCostPayedByEquity = plant.getActualInvestedCapital()
                    * (1 - agent.getDebtRatioOfInvestments());
            double investmentCostPayedByDebt = plant.getActualInvestedCapital() * agent.getDebtRatioOfInvestments();
            double downPayment = investmentCostPayedByEquity;
            createSpreadOutDownPayments(agent, manufacturer, downPayment, plant);

            double amount = determineLoanAnnuities(investmentCostPayedByDebt, plant.getTechnology()
                    .getDepreciationTime(), agent.getLoanInterestRate());
            // logger.warn("Loan amount is: " + amount);
            Loan loan = reps.loanRepository.createLoan(agent, bigbank, amount, plant.getTechnology()
                    .getDepreciationTime(), getCurrentTick(), plant);
            // Create the loan
            plant.createOrUpdateLoan(loan);

        } else {
            // logger.warn("{} found no suitable technology anymore to invest in at tick "
            // + getCurrentTick(), agent);
            // agent will not participate in the next round of investment if
            // he does not invest now
            setNotWillingToInvest(agent);
        }
    }

    // }

    // stores expected gross profit for a power plant for a tick
    public void putOperatingProfitESM(PowerPlant plant, double revenue) {
        logger.warn("Putting Operating Profit ESM of" + revenue);
        expectedESMOperatingRevenueMap.put(plant, revenue);

    }

    // returns expected gross profit for a power plant for a tick
    public double getOperatingProfit(PowerPlant plant) {
        logger.warn("Trying to get operating Profit ESM of" + plant.getName());
        return expectedESMOperatingRevenueMap.get(plant);
    }

    // stores running hours for a power plant for a tick
    public void putRunningHours(PowerPlant plant, Double hours) {
        logger.warn("Store Running hours of {} for plant" + plant.getName(), hours);
        runningHoursMap.put(plant, hours);
    }

    // returns running hours for a power plant for a tick
    public double getRunningHours(PowerPlant plant) {
        logger.warn("Trying to get Running hours for plant " + plant.getName());
        return runningHoursMap.get(plant);
    }

    // Creates n downpayments of equal size in each of the n building years of a
    // power plant
    @Transactional
    private void createSpreadOutDownPayments(EnergyProducer agent, PowerPlantManufacturer manufacturer,
            double totalDownPayment, PowerPlant plant) {
        int buildingTime = (int) plant.getActualLeadtime();
        reps.nonTransactionalCreateRepository.createCashFlow(agent, manufacturer, totalDownPayment / buildingTime,
                CashFlow.DOWNPAYMENT, getCurrentTick(), plant);
        Loan downpayment = reps.loanRepository.createLoan(agent, manufacturer, totalDownPayment / buildingTime,
                buildingTime - 1, getCurrentTick(), plant);
        plant.createOrUpdateDownPayment(downpayment);
    }

    @Transactional
    private void setNotWillingToInvest(EnergyProducer agent) {
        agent.setWillingToInvest(false);
    }

    /**
     * Predicts fuel prices for {@link futureTimePoint} using a geometric trend
     * regression forecast. Only predicts fuels that are traded on a commodity
     * market.
     * 
     * @param agent
     * @param futureTimePoint
     * @return Map<Substance, Double> of predicted prices.
     */
    public Map<Substance, Double> predictFuelPrices(EnergyProducer agent, long futureTimePoint) {
        // Fuel Prices
        Map<Substance, Double> expectedFuelPrices = new HashMap<Substance, Double>();
        for (Substance substance : reps.substanceRepository.findAllSubstancesTradedOnCommodityMarkets()) {
            // Find Clearing Points for the last 5 years (counting current year
            // as one of the last 5 years).
            Iterable<ClearingPoint> cps = reps.clearingPointRepository
                    .findAllClearingPointsForSubstanceTradedOnCommodityMarkesAndTimeRange(substance, getCurrentTick()
                            - (agent.getNumberOfYearsBacklookingForForecasting() - 1), getCurrentTick());
            // logger.warn("{}, {}",
            // getCurrentTick()-(agent.getNumberOfYearsBacklookingForForecasting()-1),
            // getCurrentTick());
            // Create regression object
            GeometricTrendRegression gtr = new GeometricTrendRegression();
            for (ClearingPoint clearingPoint : cps) {
                // logger.warn("CP {}: {} , in" + clearingPoint.getTime(),
                // substance.getName(), clearingPoint.getPrice());
                gtr.addData(clearingPoint.getTime(), clearingPoint.getPrice());
            }
            expectedFuelPrices.put(substance, gtr.predict(futureTimePoint));
            // logger.warn("Forecast {}: {}, in Step " + futureTimePoint,
            // substance, expectedFuelPrices.get(substance));
        }
        return expectedFuelPrices;
    }

    // Create a powerplant investment and operation cash-flow in the form of a
    // map. If only investment, or operation costs should be considered set
    // totalInvestment or operatingProfit to 0
    private TreeMap<Integer, Double> calculateSimplePowerPlantInvestmentCashFlow(int depriacationTime,
            int buildingTime, double totalInvestment, double operatingProfit) {
        TreeMap<Integer, Double> investmentCashFlow = new TreeMap<Integer, Double>();
        double equalTotalDownPaymentInstallement = totalInvestment / buildingTime;
        for (int i = 0; i < buildingTime; i++) {
            investmentCashFlow.put(new Integer(i), -equalTotalDownPaymentInstallement);
        }
        for (int i = buildingTime; i < depriacationTime + buildingTime; i++) {
            investmentCashFlow.put(new Integer(i), operatingProfit);
        }

        return investmentCashFlow;
    }

    private double npv(TreeMap<Integer, Double> netCashFlow, double wacc) {
        double npv = 0;
        for (Integer iterator : netCashFlow.keySet()) {
            npv += netCashFlow.get(iterator).doubleValue() / Math.pow(1 + wacc, iterator.intValue());
        }
        return npv;
    }

    public double determineExpectedMarginalCost(PowerPlant plant, Map<Substance, Double> expectedFuelPrices,
            double expectedCO2Price) {
        double mc = determineExpectedMarginalFuelCost(plant, expectedFuelPrices);
        double co2Intensity = plant.calculateEmissionIntensity();
        mc += co2Intensity * expectedCO2Price;
        return mc;
    }

    public double determineExpectedMarginalFuelCost(PowerPlant powerPlant, Map<Substance, Double> expectedFuelPrices) {
        double fc = 0d;
        for (SubstanceShareInFuelMix mix : powerPlant.getFuelMix()) {
            double amount = mix.getShare();
            double fuelPrice = expectedFuelPrices.get(mix.getSubstance());
            fc += amount * fuelPrice;
        }
        return fc;
    }

    private PowerGridNode getNodeForZone(Zone zone) {
        for (PowerGridNode node : reps.genericRepository.findAll(PowerGridNode.class)) {
            if (node.getZone().equals(zone)) {
                return node;
            }
        }
        return null;
    }

    private class CapacityMarketInformation {

        double expectedCapacityMarketPrice;

        double peakSegmentLoad = 0;
        double capacitySum;
        Map<PowerPlant, Double> marginalCMCostMap = new HashMap<PowerPlant, Double>();
        Map<PowerPlant, Double> meritOrder;

        CapacityMarketInformation(ElectricitySpotMarket market, Map<ElectricitySpotMarket, Double> expectedDemand,
                long futureTimePoint) {

            double expectedCMDemandTarget;
            double expectedCMDemand = 0d;
            double demandFactor = expectedDemand.get(market).doubleValue();
            Zone zone = market.getZone();
            double supply = 0d;
            double capacityPrice = 0d;
            Regulator regulator = reps.capacityMarketRepository.findRegulatorForZone(zone);
            // calculate expected demand for Capacity Market
            for (SegmentLoad segmentLoad : market.getLoadDurationCurve()) {
                if (segmentLoad.getBaseLoad() > peakSegmentLoad) {
                    peakSegmentLoad = segmentLoad.getBaseLoad();

                }
            }

            logger.warn("Capacity Market Information");
            logger.warn("The Regulator is " + reps.regulatorRepository.findRegulatorForZone(zone));
            logger.warn("Peak Segment Load is " + peakSegmentLoad);
            logger.warn("Demand Factor is " + demandFactor);

            expectedCMDemandTarget = regulator.getReserveMargin() * peakSegmentLoad * demandFactor;

            // get merit order for this market
            double marginalCostCapacity = 0d;
            for (PowerPlant plant : reps.powerPlantRepository.findExpectedOperationalPowerPlantsInMarket(market,
                    futureTimePoint)) {
                if (getOperatingProfit(plant) >= 0)
                    marginalCostCapacity = 0;
                else
                    marginalCostCapacity = -getOperatingProfit(plant);
                marginalCMCostMap.put(plant, marginalCostCapacity);
                capacitySum += plant.getActualNominalCapacity();
            }
            MapValueComparator comp = new MapValueComparator(marginalCMCostMap);
            meritOrder = new TreeMap<PowerPlant, Double>(comp);
            meritOrder.putAll(marginalCMCostMap);

            // get capacity price for this merit order, with the demandTarget
            long numberOfSegments = reps.segmentRepository.count();

            for (Entry<PowerPlant, Double> plantCost : meritOrder.entrySet()) {
                PowerPlant plant = plantCost.getKey();
                // Determine max available capacity in the future
                double plantCapacity = plant.getExpectedAvailableCapacity(futureTimePoint, null, numberOfSegments);

                if (plantCost.getValue() < regulator.getCapacityMarketPriceCap()) {

                    expectedCMDemand = expectedCMDemandTarget
                            * (1 - regulator.getReserveDemandLowerMargin())
                            + ((regulator.getCapacityMarketPriceCap() - plantCost.getValue())
                                    * (regulator.getReserveDemandUpperMargin() + regulator
                                            .getReserveDemandLowerMargin()) * expectedCMDemandTarget)
                                            / regulator.getCapacityMarketPriceCap();

                    if (supply < expectedCMDemand) {
                        supply += plantCapacity;
                        capacityPrice = -getOperatingProfit(plant);
                    }

                }

            }
            if (supply >= expectedCMDemand) {
                expectedCapacityMarketPrice = capacityPrice;
            } else {
                capacityPrice = regulator.getCapacityMarketPriceCap()
                        * (1 + ((expectedCMDemandTarget * (1 - regulator.getReserveDemandLowerMargin()) - supply) / ((regulator
                                .getReserveDemandUpperMargin() + regulator.getReserveDemandLowerMargin()) * expectedCMDemandTarget)));
                expectedCapacityMarketPrice = capacityPrice;
            }

        }
    }

    private class MarketInformation {

        Map<Segment, Double> expectedElectricityPricesPerSegment;
        double maxExpectedLoad = 0d;
        Map<PowerPlant, Double> meritOrder;
        double capacitySum;

        MarketInformation(ElectricitySpotMarket market, Map<ElectricitySpotMarket, Double> expectedDemand,
                Map<Substance, Double> fuelPrices, double co2price, long time) {
            // determine expected power prices
            expectedElectricityPricesPerSegment = new HashMap<Segment, Double>();
            Map<PowerPlant, Double> marginalCostMap = new HashMap<PowerPlant, Double>();
            capacitySum = 0d;

            // get merit order for this market
            for (PowerPlant plant : reps.powerPlantRepository.findExpectedOperationalPowerPlantsInMarket(market, time)) {

                double plantMarginalCost = determineExpectedMarginalCost(plant, fuelPrices, co2price);
                marginalCostMap.put(plant, plantMarginalCost);
                capacitySum += plant.getActualNominalCapacity();
            }

            // get difference between technology target and expected operational
            // capacity
            for (PowerGeneratingTechnologyTarget pggt : reps.powerGenerationTechnologyTargetRepository
                    .findAllByMarket(market)) {
                double expectedTechnologyCapacity = reps.powerPlantRepository
                        .calculateCapacityOfExpectedOperationalPowerPlantsInMarketAndTechnology(market,
                                pggt.getPowerGeneratingTechnology(), time);
                double targetDifference = pggt.getTrend().getValue(time) - expectedTechnologyCapacity;
                if (targetDifference > 0) {
                    PowerPlant plant = new PowerPlant();
                    plant.specifyNotPersist(getCurrentTick(), new EnergyProducer(),
                            reps.powerGridNodeRepository.findFirstPowerGridNodeByElectricitySpotMarket(market),
                            pggt.getPowerGeneratingTechnology());
                    plant.setActualNominalCapacity(targetDifference);
                    double plantMarginalCost = determineExpectedMarginalCost(plant, fuelPrices, co2price);
                    marginalCostMap.put(plant, plantMarginalCost);
                    capacitySum += targetDifference;
                }
            }

            MapValueComparator comp = new MapValueComparator(marginalCostMap);
            meritOrder = new TreeMap<PowerPlant, Double>(comp);
            meritOrder.putAll(marginalCostMap);

            long numberOfSegments = reps.segmentRepository.count();

            double demandFactor = expectedDemand.get(market).doubleValue();

            // find expected prices per segment given merit order
            for (SegmentLoad segmentLoad : market.getLoadDurationCurve()) {

                double expectedSegmentLoad = segmentLoad.getBaseLoad() * demandFactor;

                if (expectedSegmentLoad > maxExpectedLoad) {
                    maxExpectedLoad = expectedSegmentLoad;
                }

                double segmentSupply = 0d;
                double segmentPrice = 0d;
                double totalCapacityAvailable = 0d;

                for (Entry<PowerPlant, Double> plantCost : meritOrder.entrySet()) {
                    PowerPlant plant = plantCost.getKey();
                    double plantCapacity = 0d;
                    // Determine available capacity in the future in this
                    // segment
                    plantCapacity = plant
                            .getExpectedAvailableCapacity(time, segmentLoad.getSegment(), numberOfSegments);
                    totalCapacityAvailable += plantCapacity;
                    // logger.warn("Capacity of plant " + plant.toString() +
                    // " is " +
                    // plantCapacity/plant.getActualNominalCapacity());
                    if (segmentSupply < expectedSegmentLoad) {
                        segmentSupply += plantCapacity;
                        segmentPrice = plantCost.getValue();
                    }

                }

                // logger.warn("Segment " +
                // segmentLoad.getSegment().getSegmentID() + " supply equals " +
                // segmentSupply + " and segment demand equals " +
                // expectedSegmentLoad);

                // Find strategic reserve operator for the market.
                double reservePrice = 0;
                double reserveVolume = 0;
                for (StrategicReserveOperator operator : strategicReserveOperatorRepository.findAll()) {
                    ElectricitySpotMarket market1 = reps.marketRepository.findElectricitySpotMarketForZone(operator
                            .getZone());
                    if (market.getNodeId().intValue() == market1.getNodeId().intValue()) {
                        reservePrice = operator.getReservePriceSR();
                        reserveVolume = operator.getReserveVolume();
                    }
                }

                if (segmentSupply >= expectedSegmentLoad
                        && ((totalCapacityAvailable - expectedSegmentLoad) <= (reserveVolume))) {
                    expectedElectricityPricesPerSegment.put(segmentLoad.getSegment(), reservePrice);
                    // logger.warn("Price: "+
                    // expectedElectricityPricesPerSegment);
                } else if (segmentSupply >= expectedSegmentLoad
                        && ((totalCapacityAvailable - expectedSegmentLoad) > (reserveVolume))) {
                    expectedElectricityPricesPerSegment.put(segmentLoad.getSegment(), segmentPrice);
                    // logger.warn("Price: "+
                    // expectedElectricityPricesPerSegment);
                } else {
                    expectedElectricityPricesPerSegment.put(segmentLoad.getSegment(), market.getValueOfLostLoad());
                }

            }
        }
    }

}
