# Accessing data in S3 with Spark

In this guide we walk through the process of configuring an [Apache Spark](https://spark.apache.org/) session to work 
with data files stored in Amazon [S3](https://aws.amazon.com/s3/) and version history in a local Nessie Server.

Docker is used at the runtime environments for Nessie. Spark is assumed to be installed locally.

## Setting up Nessie Server

Start the Nessie server container from the `projectnessie/nessie` Docker image in default mode.

```shell
docker run -p 19120:19120 projectnessie/nessie:latest
```

Note: this example will run the Nessie Server using in-memory storage for table metadata. If/when the container
is deleted, Nessie data about table changes will be lost, yet the data files in S3 will remain.

## Setting up Spark Session

Configure an AWS profile (e.g. called `demo`) in `~/.aws/credentials` (or other location appropriate for your OS)
and export the profile name in the `AWS_PROFILE` environment variable. For example:

```shell
export AWS_PROFILE=demo
```

Create an S3 bucket of your own. This guide uses the bucket name `spark-demo1`.

Start a Spark session:

```shell
spark-sql \
 --packages \
org.apache.iceberg:iceberg-spark-runtime-3.2_2.12:0.13.1,\
software.amazon.awssdk:bundle:2.17.178,\
software.amazon.awssdk:url-connection-client:2.17.178 \
 --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions  \
 --conf spark.sql.catalog.nessie=org.apache.iceberg.spark.SparkCatalog \
 --conf spark.sql.catalog.nessie.warehouse=s3://spark-demo1 \
 --conf spark.sql.catalog.nessie.catalog-impl=org.apache.iceberg.nessie.NessieCatalog \
 --conf spark.sql.catalog.nessie.io-impl=org.apache.iceberg.aws.s3.S3FileIO \
 --conf spark.sql.catalog.nessie.uri=http://localhost:19120/api/v1 \
 --conf spark.sql.catalog.nessie.ref=main \
 --conf spark.sql.catalog.nessie.cache-enabled=false
```

Note: `spark-demo1` is the name of the S3 bucket that will hold table data files.

Note: the `--packages` option lists modules required for Iceberg to write data files into S3.
Please refer to [Iceberg documentation](https://iceberg.apache.org/docs/latest/aws/#iceberg-aws-integrations)
for the most up-to-date information on how to connect Iceberg to S3.

Note: the word `nessie` in configuration property names is the name of the Nessie catalog in the Spark session.
A different name can be chosen according the user's liking.

Then, in `spark-sql` issue a `use` statement to make `nessie` the current catalog:
```
spark-sql> use nessie
```

This command will establish a connection to the Nessie Server. When it is done, it will be possible to create tables
and run DML. For example:

```
spark-sql> CREATE TABLE demo (id bigint, data string);
Time taken: 1.615 seconds
spark-sql> show tables;
demo
Time taken: 0.425 seconds, Fetched 1 row(s)
spark-sql> INSERT INTO demo (id, data) VALUES (1, 'a');
Time taken: 4.017 seconds
spark-sql> SELECT * FROM demo;
1	a
Time taken: 3.225 seconds, Fetched 1 row(s)
```

Branches, merges and other git-like commands can be run as well, as explained in the 
[Getting Started](../try/index.md) guide. 

Note: The above example uses the `spark-sql` shell, but the same configuration options apply to `spark-shell`.

# Authentication

This example uses implicit AWS authentication via credentials configured in a credentials file plus the `AWS_PROFILE`
environment variable.

The Nessie Server in this example does not require authentication.

If the Nessie Server runs with authentication enabled, additional configuration parameters will be required in the
Spark session. Please refer to the [Authentication in Tools](../tools/auth_config.md) section for details.
