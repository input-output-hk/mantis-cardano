# Mantis-Cardano

This started as a spin-off of [Mantis](https://github.com/input-output-hk/mantis), in order to deliver the KEVM and IELE testnets.

It supports Pluggable VMs and Consensus, and work has started for a Cardano CL layer via sidechains.

## Logs

Mantis uses [Riemann](http://riemann.io) as well as normal logs. By default Riemann events are sent to stdout, however this can make things quite noisy. It is recommended that you run Riemann locally. This can be done using docker:

`docker run -p 5555:5555 -p 5556:5556 riemannio/riemann`

and uncommenting the `riemann` section of [application.conf](./src/main/resources/application.conf).

You can then view Riemann events by using tools such as:

[rcl](https://gitlab.com/shmish111/rcl)

`rcl 'service =~ "mantis %"'`

A useful query with `jq` filtering is:

`rcl 'service =~ "mantis %" and not service =~ "mantis health %" and not service =~ "mantis riemann %"' | jq -c 'del(.[] | nulls) | del(.host) | del(.ttl) | del(.time)''`

[riemann dash](https://hub.docker.com/r/include/docker-riemann-dash/)

`docker run -p 4567:4567 include/docker-riemann-dash`
