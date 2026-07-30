from pyspark.sql import SparkSession
spark = SparkSession.builder.appName("v").getOrCreate()
df = spark.read.parquet("/opt/spark-apps/data/indicators")
print(f"전체 {df.count()}건")
df.select("stock_code","eps","bps","per","pbr","roe","debt_ratio","dividend_yield","roa","momentum","f_score","eps_growth_rate") \
  .orderBy("stock_code").show(30, truncate=False)

print("=== null 비율 ===")
from pyspark.sql import functions as F
df.select([F.round(F.sum(F.col(c).isNull().cast("int"))/F.count("*")*100,1).alias(c) for c in
           ["eps","bps","per","pbr","roe","debt_ratio","dividend_yield","roa","momentum","f_score","eps_growth_rate"]]).show()
spark.stop()
