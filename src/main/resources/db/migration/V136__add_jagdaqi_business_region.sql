-- Add Jagdaqi without falsifying its administrative hierarchy.  It remains a
-- county-level region under Daxing'anling Prefecture while being projected as
-- a top-level FORMAL_BUSINESS navigation region.

ALTER TABLE platform.monitoring_scope_region
  ADD COLUMN business_parent_code varchar(12) REFERENCES platform.region(code),
  ADD COLUMN business_root boolean NOT NULL DEFAULT false;

UPDATE platform.monitoring_scope_region scope
   SET business_parent_code=region.parent_code,
       business_root=(region.parent_code IS NULL)
  FROM platform.region region
 WHERE region.code=scope.region_code;

ALTER TABLE platform.monitoring_scope_region
  ADD CONSTRAINT monitoring_scope_business_navigation_shape
  CHECK (
    (business_root AND business_parent_code IS NULL)
    OR (NOT business_root AND business_parent_code IS NOT NULL)
  );

CREATE FUNCTION platform.fill_monitoring_scope_business_navigation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  administrative_parent varchar(12);
BEGIN
  IF NOT NEW.business_root AND NEW.business_parent_code IS NULL THEN
    SELECT parent_code INTO administrative_parent
      FROM platform.region
     WHERE code=NEW.region_code;
    NEW.business_root=(administrative_parent IS NULL);
    NEW.business_parent_code=administrative_parent;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER monitoring_scope_business_navigation_default
BEFORE INSERT OR UPDATE OF region_code,business_parent_code,business_root
ON platform.monitoring_scope_region
FOR EACH ROW
EXECUTE FUNCTION platform.fill_monitoring_scope_business_navigation();

DO $govern_regions$
DECLARE
  operation_code varchar;
BEGIN
  operation_code:=CASE WHEN EXISTS(
    SELECT 1 FROM platform.region WHERE code='232700') THEN 'UPDATE' ELSE 'INSERT' END;
  PERFORM platform.govern_master_data_change(
    'REGION','232700',operation_code,
    jsonb_build_object(
      'code','232700','name','大兴安岭地区','parent_code',NULL,
      'administrative_level','PREFECTURE','sort_order',40),
    clock_timestamp(),
    'V136_JAGDAQI_SOURCE_APPLICANT','V136_JAGDAQI_INDEPENDENT_REVIEWER',
    '黑龙江省行政区划与加格达奇区政府管理关系核验；保留真实行政父级');

  operation_code:=CASE WHEN EXISTS(
    SELECT 1 FROM platform.region WHERE code='232761') THEN 'UPDATE' ELSE 'INSERT' END;
  PERFORM platform.govern_master_data_change(
    'REGION','232761',operation_code,
    jsonb_build_object(
      'code','232761','name','加格达奇区','parent_code','232700',
      'administrative_level','COUNTY','sort_order',1),
    clock_timestamp(),
    'V136_JAGDAQI_SOURCE_APPLICANT','V136_JAGDAQI_INDEPENDENT_REVIEWER',
    '232761 政府业务口径、官方管理关系与真实边界来源交叉核验');
END;
$govern_regions$;

CREATE TABLE platform.region_code_provenance (
  region_code varchar(12) PRIMARY KEY REFERENCES platform.region(code),
  code_type varchar(40) NOT NULL,
  source_name varchar(160) NOT NULL,
  source_url text NOT NULL,
  source_revision varchar(120) NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  CHECK (code_type IN ('MCA_ADMIN','STATISTICAL_OR_OPERATIONAL'))
);

INSERT INTO platform.region_code_provenance(
  region_code,code_type,source_name,source_url,source_revision)
VALUES (
  '232761','STATISTICAL_OR_OPERATIONAL','中国政府采购网地方公告',
  'https://www.ccgp.gov.cn/cggg/dfgg/zbgg/202607/t20260716_26945376.htm',
  '行政区域：加格达奇区；项目编号前缀 [232761]，核验于 2026-08-25'
);

INSERT INTO platform.monitoring_scope_region(
  scope_code,region_code,included,exclusion_reason,business_parent_code,business_root)
VALUES ('FORMAL_BUSINESS','232761',true,NULL,NULL,true)
ON CONFLICT(scope_code,region_code) DO UPDATE SET
  included=true,
  exclusion_reason=NULL,
  business_parent_code=NULL,
  business_root=true;

WITH source_geometry AS (
  SELECT ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON($geo$
{"type":"Polygon","coordinates":[[[124.1763933,50.2259715],[124.1759292,50.2238057],[124.178095,50.2205571],[124.1819624,50.2151427],[124.1849017,50.2146786],[124.1979421,50.2038093],[124.2034529,50.1952425],[124.1996668,50.1899583],[124.1979462,50.1840168],[124.2089618,50.1811734],[124.2110277,50.1767763],[124.2130953,50.167538],[124.2210078,50.1671066],[124.2351056,50.1638163],[124.2433511,50.1719617],[124.2464414,50.1785624],[124.2481571,50.1882407],[124.2405985,50.1897793],[124.2381907,50.2027535],[124.242655,50.2137478],[124.2264379,50.2275958],[124.2388137,50.2272864],[124.248869,50.2271317],[124.257068,50.2274411],[124.2618636,50.2280599],[124.2666592,50.2288334],[124.2711454,50.2297616],[124.2756316,50.2291428],[124.2747034,50.2251207],[124.2720736,50.2228002],[124.2692117,50.2202477],[124.2685929,50.2162256],[124.2684382,50.2132863],[124.2715321,50.2100377],[124.2732338,50.2074078],[124.2729244,50.2029216],[124.2732338,50.1959602],[124.2764825,50.1916287],[124.279267,50.187916],[124.282361,50.186369],[124.2872339,50.1852088],[124.2914107,50.18459],[124.2957423,50.1835071],[124.2996097,50.181496],[124.3036318,50.1797944],[124.3085821,50.179485],[124.3126043,50.1780927],[124.3173999,50.1767004],[124.3200297,50.1767004],[124.3232784,50.1776286],[124.3273005,50.179485],[124.3313226,50.183043],[124.3337978,50.1856729],[124.3351727,50.186393],[124.3370464,50.1873745],[124.3453227,50.1911646],[124.3496543,50.194568],[124.3522841,50.1973525],[124.3522841,50.2001371],[124.3505825,50.2026122],[124.3473338,50.2061703],[124.3434664,50.2084907],[124.3395989,50.2128222],[124.3368144,50.2173085],[124.3371238,50.2213306],[124.3383614,50.2248886],[124.3419194,50.2287561],[124.3445493,50.2310765],[124.3454774,50.2335517],[124.3457095,50.2421374],[124.3447813,50.2456954],[124.3450907,50.2486347],[124.3477206,50.2517286],[124.3523615,50.2540491],[124.3571571,50.2554413],[124.3611792,50.2563695],[124.361798,50.2577618],[124.3604057,50.260237],[124.3585494,50.2624027],[124.355146,50.2645685],[124.3523615,50.2664248],[124.3506598,50.2695188],[124.3471018,50.2744691],[124.3416874,50.27911],[124.3404498,50.2828228],[124.3395216,50.2874637],[124.3412233,50.2907123],[124.3412741,50.2907526],[124.3457095,50.2942704],[124.3534444,50.2984472],[124.3591682,50.3009223],[124.3658975,50.3036295],[124.3683726,50.3065688],[124.3677538,50.3085799],[124.3665163,50.3091986],[124.3592455,50.3091986],[124.3502731,50.3101268],[124.3457868,50.3109003],[124.3422288,50.3122926],[124.3402177,50.3149224],[124.3394442,50.3189446],[124.3383614,50.3257512],[124.3377426,50.3302375],[124.3391349,50.3319391],[124.3411459,50.3320938],[124.3425382,50.3327126],[124.3426929,50.3354972],[124.3437758,50.3378176],[124.346715,50.3402928],[124.3499637,50.3438508],[124.3508918,50.3461713],[124.3510465,50.3505028],[124.3515106,50.3539062],[124.3539858,50.3573095],[124.3566156,50.3591659],[124.3599416,50.359862],[124.3652013,50.359862],[124.3718533,50.3587791],[124.3757208,50.3580056],[124.3812899,50.3578509],[124.3873231,50.3584697],[124.3936656,50.3600167],[124.3961408,50.3607902],[124.3983066,50.3620278],[124.3998535,50.3641935],[124.4004723,50.3672875],[124.4017099,50.3697626],[124.4051132,50.371619],[124.4092901,50.3725472],[124.4147045,50.3733207],[124.4191907,50.3736301],[124.4219752,50.3756411],[124.4238316,50.3776522],[124.4261521,50.3796632],[124.4304836,50.3819837],[124.43234,50.3838401],[124.43234,50.386934],[124.4312571,50.3897186],[124.4298648,50.392039],[124.4242184,50.3979949],[124.4225167,50.4012435],[124.4220526,50.4034093],[124.4234449,50.407122],[124.4260747,50.4130005],[124.4260747,50.4156304],[124.4253012,50.4174867],[124.4234449,50.4207354],[124.4201962,50.4256857],[124.4169476,50.4309454],[124.4147818,50.435741],[124.4136989,50.440846],[124.4119973,50.4440947],[124.409058,50.4479621],[124.4085939,50.4501279],[124.4075564,50.4519567],[124.4099571,50.4530439],[124.4127129,50.4538189],[124.41486,50.4545294],[124.4198304,50.4556811],[124.4237358,50.4555627],[124.4260943,50.4561493],[124.4281991,50.4570696],[124.4308872,50.4569728],[124.4333555,50.4575002],[124.4357365,50.4746049],[124.4350308,50.4774281],[124.4342493,50.4805543],[124.4302595,50.4965116],[124.4330061,50.5157266],[124.4357527,50.5384251],[124.4220198,50.5506429],[124.3808211,50.5471524],[124.3643416,50.5384251],[124.3368757,50.5401707],[124.3121565,50.5349337],[124.2929304,50.5558781],[124.2517317,50.5593679],[124.1968001,50.5541331],[124.1336287,50.5680913],[124.0979231,50.5715802],[124.0689377,50.5645768],[124.069273,50.5581287],[124.0712565,50.5554083],[124.0754717,50.5520717],[124.0800574,50.5477449],[124.0819169,50.5450245],[124.0831562,50.5425501],[124.0822893,50.5398268],[124.0796885,50.5382167],[124.0628286,50.5379485],[124.046881,50.5374862],[124.0390581,50.5363545],[124.0190523,50.5366735],[124.0163187,50.5346853],[124.0171895,50.5309758],[124.0178132,50.5228108],[124.0173162,50.5203345],[124.0160735,50.5167421],[124.011351,50.5137589],[124.007124,50.5120139],[124.0020274,50.5113801],[123.9944429,50.5114815],[123.9849956,50.5107108],[123.9790291,50.5093329],[123.976296,50.508459],[123.9759229,50.5063547],[123.9779124,50.5036373],[123.9794047,50.5014139],[123.9800269,50.4988176],[123.9811453,50.4959752],[123.9840046,50.4942508],[123.9891023,50.4924093],[123.9939509,50.4908149],[123.9952572,50.4884065],[123.9943875,50.4849391],[123.9951346,50.4804865],[123.9961302,50.4771491],[123.9951352,50.4746708],[123.9926496,50.4725606],[123.9925255,50.4705804],[123.9928993,50.4664979],[123.991657,50.4645147],[123.9906634,50.4607993],[123.9905402,50.4567158],[123.9876806,50.4546046],[123.9871844,50.4526234],[123.9895466,50.45065],[123.9935254,50.4481875],[123.9938991,50.4468273],[123.9922828,50.4445961],[123.9899212,50.442609],[123.986566,50.4395058],[123.987934,50.4377775],[123.9910416,50.435312],[123.998067,50.4327964],[123.997694,50.4303211],[123.995208,50.4269728],[123.9904848,50.4231235],[123.9883732,50.4191581],[123.9865714,50.4156268],[123.9816001,50.4097973],[123.9731492,50.4032962],[123.9664662,50.3989646],[123.9626916,50.3979127],[123.9571225,50.3975414],[123.9568054,50.3975198],[123.9516779,50.3971703],[123.9466031,50.3984077],[123.9424853,50.3984077],[123.9423953,50.3984077],[123.9380638,50.3977889],[123.9327422,50.3960563],[123.9270494,50.3927149],[123.9238317,50.3901159],[123.9232129,50.3866507],[123.9229507,50.3820266],[123.9219753,50.379349],[123.9197477,50.3769976],[123.9164062,50.3741512],[123.9163608,50.3741162],[123.9131885,50.371676],[123.9077615,50.375177],[123.9015736,50.3778069],[123.8970874,50.3791992],[123.8912089,50.3804367],[123.8853304,50.3816743],[123.8820818,50.3826025],[123.8811536,50.3843042],[123.8816177,50.3866246],[123.8816177,50.3895639],[123.879916,50.3906468],[123.8766674,50.3915749],[123.8726452,50.3937407],[123.8706342,50.3960612],[123.8718718,50.3990004],[123.8723358,50.4011662],[123.8701701,50.4027131],[123.867927,50.403564],[123.8606562,50.4051109],[123.8569435,50.4072767],[123.854623,50.4077408],[123.8502915,50.4083596],[123.8416284,50.4085143],[123.8369875,50.4092878],[123.8332748,50.4100613],[123.8315731,50.4133099],[123.8300261,50.4160945],[123.8304902,50.4199619],[123.8312637,50.4241387],[123.8307996,50.4293984],[123.829098,50.4312548],[123.8247664,50.4346581],[123.8227554,50.4377521],[123.8202802,50.44394],[123.8184238,50.4481168],[123.8148658,50.4502826],[123.810689,50.4518295],[123.8045011,50.4538406],[123.8006337,50.4555423],[123.7970756,50.4555423],[123.7930535,50.4544594],[123.7893408,50.4515201],[123.7864015,50.4471886],[123.7826888,50.4440947],[123.7755727,50.4382162],[123.7730975,50.4355863],[123.7732529,50.4326471],[123.775418,50.432183],[123.779082,50.4300586],[123.7818628,50.4271171],[123.7822247,50.4250669],[123.7825341,50.4221276],[123.7816832,50.4184923],[123.7782799,50.411995],[123.7765782,50.4082822],[123.7764235,50.400238],[123.772092,50.3962159],[123.768534,50.3918843],[123.7638931,50.386934],[123.758324,50.3765693],[123.758788,50.3730113],[123.7620367,50.3697626],[123.7646665,50.3682156],[123.7680699,50.3675969],[123.770545,50.3675969],[123.7725561,50.368525],[123.7748766,50.3714643],[123.7775064,50.3734754],[123.7798269,50.3722378],[123.7799816,50.3705361],[123.7796722,50.3677516],[123.7785893,50.3629559],[123.776965,50.3583924],[123.7727882,50.3489558],[123.770313,50.3429226],[123.770313,50.3404475],[123.7723241,50.3392099],[123.776965,50.3392099],[123.7819153,50.3399834],[123.7848545,50.3393646],[123.7855887,50.3372606],[123.7882579,50.3341049],[123.7902213,50.3309145],[123.7899136,50.3278222],[123.7894519,50.3247298],[123.7905818,50.3221916],[123.7939817,50.321265],[123.7980038,50.3206462],[123.8023353,50.3195634],[123.8037276,50.3172429],[123.8041917,50.309508],[123.8066669,50.3062594],[123.8100702,50.3053312],[123.8145564,50.3053312],[123.822446,50.3061047],[123.826004,50.3056406],[123.8268548,50.3029334],[123.8274736,50.2982925],[123.8310317,50.2931875],[123.8339414,50.2911966],[123.8401588,50.2883919],[123.8444903,50.2871543],[123.849286,50.2859167],[123.8545457,50.285762],[123.8574849,50.2839056],[123.8608882,50.2784912],[123.863054,50.2726127],[123.8635181,50.2690547],[123.8610429,50.2647232],[123.8598054,50.2610104],[123.8607335,50.2576071],[123.8610429,50.2521927],[123.8607335,50.2490988],[123.8587998,50.2453087],[123.8558806,50.2391629],[123.8558606,50.2391208],[123.8536948,50.2330876],[123.8535401,50.2289108],[123.8555512,50.2248886],[123.8591092,50.22164],[123.8651424,50.2180819],[123.8697833,50.2163803],[123.8708662,50.2139051],[123.8705568,50.2097283],[123.8717944,50.2072531],[123.8748883,50.2060156],[123.8782917,50.2066344],[123.8807668,50.2086454],[123.884789,50.2112753],[123.8886564,50.2112753],[123.8963913,50.2100377],[123.9021151,50.2086454],[123.9059825,50.2052421],[123.9093858,50.2019934],[123.9107781,50.1978166],[123.9149549,50.1911646],[123.9191318,50.1869878],[123.923618,50.1854408],[123.935607,50.1839712],[123.9416402,50.1838165],[123.9468999,50.1855182],[123.9513861,50.1898497],[123.9566459,50.1954188],[123.9625244,50.2008332],[123.9667012,50.2048553],[123.9697951,50.2073305],[123.974436,50.2084134],[123.9817068,50.2085681],[123.9874306,50.2087228],[123.9926903,50.2096509],[123.9950108,50.2119714],[123.9984141,50.2159935],[124.0002705,50.2176952],[124.0033644,50.218314],[124.0075413,50.218314],[124.0123369,50.2164576],[124.0137291,50.2141372],[124.0175192,50.2092642],[124.0198397,50.2080266],[124.0238618,50.2075625],[124.0322155,50.2078719],[124.0362376,50.2061703],[124.0413426,50.2050874],[124.0464476,50.2044686],[124.0487681,50.2024575],[124.0509338,50.1995183],[124.0543372,50.1971978],[124.0578952,50.198126],[124.0631549,50.2010652],[124.0688787,50.2053968],[124.0747572,50.2092642],[124.0795528,50.2115847],[124.0857407,50.2125128],[124.090691,50.2131316],[124.0936303,50.2160709],[124.0959507,50.2204024],[124.0967242,50.2241151],[124.0961054,50.2261262],[124.0939397,50.2286014],[124.0917739,50.2306124],[124.0916192,50.2335517],[124.0925527,50.2357249],[124.0951773,50.236955],[124.1073983,50.2372644],[124.1163521,50.2375732],[124.1163708,50.2375738],[124.1218625,50.2370324],[124.1278957,50.2354854],[124.130835,50.2325461],[124.1320726,50.228524],[124.1348571,50.2271317],[124.1405809,50.226513],[124.1444483,50.2254301],[124.1493987,50.2248113],[124.1521832,50.2246566],[124.1552771,50.2262036],[124.1558959,50.2294522],[124.1568241,50.2327008],[124.1588352,50.2345572],[124.1617744,50.2353307],[124.1667247,50.2356401],[124.174769,50.2330102],[124.1777083,50.2313086],[124.1781465,50.2305113],[124.1786418,50.2296102],[124.1763933,50.2259715]]]}
$geo$),4326))::geometry(MultiPolygon,4326) geometry
)
INSERT INTO overview.administrative_boundary(
  region_code,geometry,source_name,source_url,source_revision,source_license,
  source_feature_id,source_effective_on,geometry_sha256,loaded_at)
SELECT
  '232761',geometry,
  'Overture Maps divisions/division_area (OpenStreetMap source)',
  'https://docs.overturemaps.org/guides/divisions/',
  'Overture 2026-08-19.0; OSM 2026-07-23T00:00:00Z; record r9355837@13',
  'ODbL-1.0','4376a992-05c5-482d-8394-a95f46162133',DATE '2026-07-23',
  encode(sha256(ST_AsEWKB(geometry)),'hex'),now()
FROM source_geometry
ON CONFLICT(region_code) DO UPDATE SET
  geometry=EXCLUDED.geometry,
  source_name=EXCLUDED.source_name,
  source_url=EXCLUDED.source_url,
  source_revision=EXCLUDED.source_revision,
  source_license=EXCLUDED.source_license,
  source_feature_id=EXCLUDED.source_feature_id,
  source_effective_on=EXCLUDED.source_effective_on,
  geometry_sha256=EXCLUDED.geometry_sha256,
  loaded_at=EXCLUDED.loaded_at;

DO $validation$
DECLARE
  boundary_geometry geometry;
  area_km2 numeric;
BEGIN
  SELECT geometry,ST_Area(geometry::geography)/1000000
    INTO boundary_geometry,area_km2
    FROM overview.administrative_boundary
   WHERE region_code='232761';

  IF boundary_geometry IS NULL
     OR NOT ST_IsValid(boundary_geometry)
     OR ST_IsEmpty(boundary_geometry)
     OR ST_XMin(boundary_geometry) NOT BETWEEN 123.75 AND 123.77
     OR ST_XMax(boundary_geometry) NOT BETWEEN 124.43 AND 124.44
     OR ST_YMin(boundary_geometry) NOT BETWEEN 50.16 AND 50.17
     OR ST_YMax(boundary_geometry) NOT BETWEEN 50.57 AND 50.58
     OR area_km2 NOT BETWEEN 1300 AND 1450 THEN
    RAISE EXCEPTION 'Jagdaqi governed boundary failed source-range validation';
  END IF;
END;
$validation$;

CREATE OR REPLACE FUNCTION overview.refresh_monitoring_scope_boundary(requested_scope_code varchar)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  expected_count integer;
  source_count integer;
  combined_geometry geometry(MultiPolygon,4326);
  combined_revisions text;
  combined_licenses text;
  combined_fingerprint text;
BEGIN
  SELECT count(*) INTO expected_count
    FROM platform.monitoring_scope_region scoped
   WHERE scoped.scope_code=requested_scope_code
     AND scoped.included
     AND scoped.business_root;

  SELECT count(*),
         ST_Multi(ST_UnaryUnion(ST_Collect(boundary.geometry))),
         string_agg(DISTINCT boundary.source_revision,', ' ORDER BY boundary.source_revision),
         string_agg(DISTINCT boundary.source_license,'; ' ORDER BY boundary.source_license),
         string_agg(boundary.region_code || ':' || boundary.geometry_sha256,'|' ORDER BY boundary.region_code)
    INTO source_count,combined_geometry,combined_revisions,combined_licenses,combined_fingerprint
    FROM platform.monitoring_scope_region scoped
    JOIN overview.administrative_boundary boundary ON boundary.region_code=scoped.region_code
   WHERE scoped.scope_code=requested_scope_code
     AND scoped.included
     AND scoped.business_root;

  IF expected_count=0 THEN
    RAISE EXCEPTION 'Cannot refresh unknown or empty monitoring scope %',requested_scope_code;
  END IF;

  IF source_count<>expected_count OR combined_geometry IS NULL THEN
    DELETE FROM overview.monitoring_scope_boundary WHERE scope_code=requested_scope_code;
    RETURN;
  END IF;

  INSERT INTO overview.monitoring_scope_boundary(
    scope_code,geometry,source_name,source_revision,source_license,
    component_geometry_fingerprint,refreshed_at)
  VALUES (
    requested_scope_code,combined_geometry,
    'Precomputed union of fully covered formal business root boundaries',
    combined_revisions,combined_licenses,combined_fingerprint,now())
  ON CONFLICT(scope_code) DO UPDATE SET
    geometry=EXCLUDED.geometry,
    source_name=EXCLUDED.source_name,
    source_revision=EXCLUDED.source_revision,
    source_license=EXCLUDED.source_license,
    component_geometry_fingerprint=EXCLUDED.component_geometry_fingerprint,
    refreshed_at=EXCLUDED.refreshed_at;
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_monitoring_scope_boundary_render(requested_scope_code varchar)
RETURNS void
LANGUAGE sql
AS $$
  WITH coverage AS (
    SELECT scope.code scope_code,
           ST_Multi(ST_UnaryUnion(ST_Collect(render.geometry))) geometry,
           string_agg(DISTINCT render.source_name, '; ' ORDER BY render.source_name)::varchar(160) source_name,
           string_agg(DISTINCT render.source_revision, ', ' ORDER BY render.source_revision)::varchar(120) source_revision,
           string_agg(DISTINCT render.source_license, '; ' ORDER BY render.source_license) source_license,
           string_agg(
             render.region_code||':'||render.source_geometry_sha256,
             ',' ORDER BY render.region_code
           ) component_geometry_fingerprint
      FROM platform.monitoring_scope scope
      JOIN overview.monitoring_scope_boundary governed_scope
        ON governed_scope.scope_code=scope.code
      JOIN platform.monitoring_scope_region member
        ON member.scope_code=scope.code AND member.included AND member.business_root
      JOIN overview.administrative_boundary_render render
        ON render.region_code=member.region_code
     WHERE scope.code=requested_scope_code
     GROUP BY scope.code
    HAVING count(*)=(
      SELECT count(*)
        FROM platform.monitoring_scope_region expected_member
       WHERE expected_member.scope_code=requested_scope_code
         AND expected_member.included
         AND expected_member.business_root
    )
  )
  INSERT INTO overview.monitoring_scope_boundary_render(
    scope_code,geometry,geo_json,simplify_tolerance,full_point_count,
    render_point_count,component_geometry_fingerprint,refreshed_at,
    source_name,source_revision,source_license
  )
  SELECT coverage.scope_code,
         coverage.geometry,
         ST_AsGeoJSON(coverage.geometry),
         0::double precision,
         ST_NPoints(coverage.geometry),
         ST_NPoints(coverage.geometry),
         coverage.component_geometry_fingerprint,
         now(),
         coverage.source_name,
         coverage.source_revision,
         coverage.source_license
    FROM coverage
  ON CONFLICT(scope_code) DO UPDATE SET
    geometry=EXCLUDED.geometry,
    geo_json=EXCLUDED.geo_json,
    simplify_tolerance=EXCLUDED.simplify_tolerance,
    full_point_count=EXCLUDED.full_point_count,
    render_point_count=EXCLUDED.render_point_count,
    component_geometry_fingerprint=EXCLUDED.component_geometry_fingerprint,
    refreshed_at=EXCLUDED.refreshed_at,
    source_name=EXCLUDED.source_name,
    source_revision=EXCLUDED.source_revision,
    source_license=EXCLUDED.source_license;
$$;

COMMENT ON COLUMN platform.monitoring_scope_region.business_root IS
  'True when a governed region is presented as a top-level business navigation region; this is independent of administrative level.';
COMMENT ON COLUMN platform.monitoring_scope_region.business_parent_code IS
  'Parent in the formal business navigation tree; administrative parent remains platform.region.parent_code.';
COMMENT ON TABLE platform.region_code_provenance IS
  'Records the explicit semantic type and evidence for region codes; operational/statistical codes must not be represented as MCA codes.';

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary('FORMAL_BUSINESS');
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');
