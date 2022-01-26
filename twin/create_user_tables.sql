CREATE TABLE IF NOT EXISTS public.device_temperature (
    device_id VARCHAR(255) NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (device_id));

CREATE TABLE IF NOT EXISTS public.energy_deposit(
    vpp_id VARCHAR(255) NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    time_stamp TIMESTAMP (0) NOT NULL,
    energy_deposited DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (vpp_id,device_id,time_stamp));

--CREATE TABLE IF NOT EXISTS public.item_popularity (
--    itemid VARCHAR(255) NOT NULL,
--    count BIGINT NOT NULL,
--    PRIMARY KEY (itemid));
