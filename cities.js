// Birth places for true-solar-time and the ascendant. [name, latitude, longitude, IANA time zone].
// Mainland China uses Asia/Shanghai everywhere (incl. Xinjiang/Tibet official time); its 1986–1991 DST
// is handled by the browser's time-zone data.
export const CITIES = [
  ['北京', 39.90, 116.41, 'Asia/Shanghai'], ['上海', 31.23, 121.47, 'Asia/Shanghai'], ['天津', 39.13, 117.20, 'Asia/Shanghai'],
  ['重庆', 29.56, 106.55, 'Asia/Shanghai'], ['广州', 23.13, 113.26, 'Asia/Shanghai'], ['深圳', 22.54, 114.06, 'Asia/Shanghai'],
  ['杭州', 30.27, 120.16, 'Asia/Shanghai'], ['南京', 32.06, 118.80, 'Asia/Shanghai'], ['苏州', 31.30, 120.59, 'Asia/Shanghai'],
  ['武汉', 30.59, 114.31, 'Asia/Shanghai'], ['成都', 30.57, 104.07, 'Asia/Shanghai'], ['西安', 34.34, 108.94, 'Asia/Shanghai'],
  ['郑州', 34.75, 113.63, 'Asia/Shanghai'], ['长沙', 28.23, 112.94, 'Asia/Shanghai'], ['济南', 36.65, 117.12, 'Asia/Shanghai'],
  ['青岛', 36.07, 120.38, 'Asia/Shanghai'], ['沈阳', 41.81, 123.43, 'Asia/Shanghai'], ['大连', 38.91, 121.61, 'Asia/Shanghai'],
  ['长春', 43.82, 125.32, 'Asia/Shanghai'], ['哈尔滨', 45.80, 126.53, 'Asia/Shanghai'], ['石家庄', 38.04, 114.51, 'Asia/Shanghai'],
  ['太原', 37.87, 112.55, 'Asia/Shanghai'], ['呼和浩特', 40.84, 111.75, 'Asia/Shanghai'], ['合肥', 31.82, 117.23, 'Asia/Shanghai'],
  ['福州', 26.07, 119.30, 'Asia/Shanghai'], ['厦门', 24.48, 118.09, 'Asia/Shanghai'], ['南昌', 28.68, 115.86, 'Asia/Shanghai'],
  ['南宁', 22.82, 108.37, 'Asia/Shanghai'], ['海口', 20.04, 110.20, 'Asia/Shanghai'], ['三亚', 18.25, 109.51, 'Asia/Shanghai'],
  ['贵阳', 26.65, 106.63, 'Asia/Shanghai'], ['昆明', 25.04, 102.71, 'Asia/Shanghai'], ['拉萨', 29.65, 91.17, 'Asia/Shanghai'],
  ['兰州', 36.06, 103.83, 'Asia/Shanghai'], ['西宁', 36.62, 101.78, 'Asia/Shanghai'], ['银川', 38.49, 106.23, 'Asia/Shanghai'],
  ['乌鲁木齐', 43.83, 87.62, 'Asia/Shanghai'], ['宁波', 29.87, 121.54, 'Asia/Shanghai'], ['温州', 28.00, 120.70, 'Asia/Shanghai'],
  ['无锡', 31.49, 120.31, 'Asia/Shanghai'], ['东莞', 23.02, 113.75, 'Asia/Shanghai'], ['佛山', 23.02, 113.12, 'Asia/Shanghai'],
  ['珠海', 22.27, 113.58, 'Asia/Shanghai'], ['汕头', 23.35, 116.68, 'Asia/Shanghai'], ['泉州', 24.87, 118.68, 'Asia/Shanghai'],
  ['烟台', 37.46, 121.45, 'Asia/Shanghai'], ['洛阳', 34.62, 112.45, 'Asia/Shanghai'], ['徐州', 34.26, 117.18, 'Asia/Shanghai'],
  ['香港', 22.32, 114.17, 'Asia/Hong_Kong'], ['澳门', 22.20, 113.55, 'Asia/Macau'], ['台北', 25.03, 121.56, 'Asia/Taipei'],
  ['高雄', 22.63, 120.30, 'Asia/Taipei'], ['新加坡', 1.35, 103.82, 'Asia/Singapore'], ['吉隆坡', 3.14, 101.69, 'Asia/Kuala_Lumpur'],
  ['曼谷', 13.76, 100.50, 'Asia/Bangkok'], ['东京', 35.68, 139.69, 'Asia/Tokyo'], ['首尔', 37.57, 126.98, 'Asia/Seoul'],
  ['伦敦', 51.51, -0.13, 'Europe/London'], ['巴黎', 48.86, 2.35, 'Europe/Paris'], ['纽约', 40.71, -74.01, 'America/New_York'],
  ['洛杉矶', 34.05, -118.24, 'America/Los_Angeles'], ['温哥华', 49.28, -123.12, 'America/Vancouver'], ['多伦多', 43.65, -79.38, 'America/Toronto'],
  ['悉尼', -33.87, 151.21, 'Australia/Sydney'], ['墨尔本', -37.81, 144.96, 'Australia/Melbourne'], ['特拉维夫', 32.09, 34.78, 'Asia/Jerusalem'],
].map(([name, lat, lon, tz]) => ({ name, lat, lon, tz }));
export const cityOf = name => CITIES.find(c => c.name === name) ?? CITIES[0];
